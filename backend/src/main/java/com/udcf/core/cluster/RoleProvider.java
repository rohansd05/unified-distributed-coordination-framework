package com.udcf.core.cluster;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The shared role provider (link L1): the single source of truth for cluster roles. Every module
 * that needs a leader, primary, coordinator, daemon or root asks here.
 *
 * <ul>
 *   <li><b>Leader.</b> Only Experiment 4's election decides it ({@link Cluster#assignLeader});
 *       {@link #leaderId()} reads it, never a crashed node, and nothing ever falls back to another
 *       rule (such as the lowest live node).</li>
 *   <li><b>No leader yet</b> (fresh start, after a reset or during a re-election):
 *       {@link #ensureLeader()} asks the {@link LeaderElector} for one election and waits a
 *       bounded time. <b>Side effect:</b> the election module then starts its election service,
 *       and with it the heartbeats of the shared failure detector, on every live node, and runs
 *       Bully from the lowest live node as a {@code REQUESTED} round. Without an election module
 *       in the context it returns empty at once.</li>
 *   <li><b>Leader changes</b> reach {@link LeaderListener}s in announcement order on one daemon
 *       thread, {@value #DISPATCHER_THREAD}, never on a socket listener or election thread.</li>
 *   <li><b>PRIMARY and BACKUP</b> are published by the replication owner through
 *       {@link #publishReplicationRoles}; never two primaries at once.</li>
 * </ul>
 *
 * <p><b>Threads.</b> {@link #ensureLeader()} blocks: call it on a request thread, never on the
 * dispatcher (it throws), a socket listener thread or an election worker (the election it waits
 * for runs there), and never while holding a lock the election path may need (a node's
 * lifecycle lock, a service's monitor, a lock a {@link LeaderListener} takes). Everything else
 * here never waits.</p>
 *
 * <p>Covered by RoleProviderTest.</p>
 */
@Component
public class RoleProvider {

    public static final String DISPATCHER_THREAD = "udcf-roles-dispatcher";

    private static final long EXIT_TIMEOUT_MILLIS = 5000;
    private static final Logger log = LoggerFactory.getLogger(RoleProvider.class);

    /** Removes a listener. */
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private final Cluster cluster;
    private final Supplier<LeaderElector> elector;
    private final List<LeaderListener> listeners = new CopyOnWriteArrayList<>();
    private final Object leaderMonitor = new Object();
    private final ExecutorService dispatcher;
    private final Runnable removeObserver;
    private volatile Thread dispatcherThread;

    /** The election module is optional: a context without it still starts, and {@link #ensureLeader()} then returns empty. */
    @Autowired
    public RoleProvider(Cluster cluster, ObjectProvider<LeaderElector> electors) {
        this(cluster, (Supplier<LeaderElector>) electors::getIfAvailable);
    }

    /** @param elector the elector, or null if there is none */
    public RoleProvider(Cluster cluster, LeaderElector elector) {
        this(cluster, (Supplier<LeaderElector>) () -> elector);
    }

    private RoleProvider(Cluster cluster, Supplier<LeaderElector> elector) {
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        this.elector = elector;
        this.dispatcher = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, DISPATCHER_THREAD);
            thread.setDaemon(true);
            dispatcherThread = thread;
            return thread;
        });
        this.removeObserver = cluster.addLeaderObserver(this::onAnnounced);
    }

    // ------------------------------------------------------------------ leader

    /** The current leader: the node holding {@link NodeRole#LEADER}; empty when there is none. */
    public Optional<Integer> leaderId() {
        return cluster.leaderId();
    }

    /**
     * The leader, electing one first if there is none: asks the {@link LeaderElector} for one
     * election, then waits at most its {@link LeaderElector#electionBoundMillis()}. See the class
     * comment for the side effect and the threads it must never run on.
     *
     * @return the leader, or empty if none could be elected in time (or there is no election module):
     *         callers answer that honestly (HTTP 409), never with a fallback
     * @throws IllegalStateException on the dispatcher thread
     */
    public Optional<Integer> ensureLeader() {
        Optional<Integer> leader = cluster.leaderId();
        if (leader.isPresent()) {
            return leader;
        }
        if (Thread.currentThread() == dispatcherThread) {
            throw new IllegalStateException("ensureLeader must not run on " + DISPATCHER_THREAD
                    + ": the leader changes it waits for are delivered there");
        }
        LeaderElector current = elector.get();
        if (current == null) {
            return Optional.empty();
        }
        current.requestElection();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(current.electionBoundMillis());
        synchronized (leaderMonitor) {
            while ((leader = cluster.leaderId()).isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(leaderMonitor, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return leader;
    }

    /**
     * Registers a listener for every later leader change (see {@link LeaderListener} for the
     * thread). A change is a hint: re-read {@link #leaderId()}.
     */
    public Registration addLeaderListener(LeaderListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * Runs {@code task} on the dispatcher thread after every leader change announced before this
     * call has been delivered, so follow-up work stays in order with the changes. Never waits;
     * dropped after {@link #close()}.
     */
    public void afterPendingChanges(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        dispatch(task);
    }

    // ------------------------------------------------------------------ replication roles

    /** The node holding {@link NodeRole#PRIMARY}, if any. */
    public Optional<Integer> primaryId() {
        return cluster.primaryId();
    }

    /**
     * Publishes the replication roles: {@code primaryId} becomes the only PRIMARY (null: none),
     * the listed live nodes BACKUP, every other node neither. Announced with
     * {@code REPLICATION_ROLES_CHANGED} when something changed (see
     * {@link Cluster#assignReplicationRoles}). Only the replication owner calls this. Never waits.
     */
    public Optional<Integer> publishReplicationRoles(Integer primaryId, Collection<Integer> backupIds) {
        return cluster.assignReplicationRoles(primaryId, backupIds);
    }

    // ------------------------------------------------------------------ internals

    /** Stops the dispatcher; later changes are no longer delivered. Spring calls it on shutdown. */
    @PreDestroy
    public void close() {
        removeObserver.run();
        dispatcher.shutdownNow();
        try {
            if (!dispatcher.awaitTermination(EXIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                log.warn("{} did not finish within {} ms", DISPATCHER_THREAD, EXIT_TIMEOUT_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Under the cluster's leader lock: wakes waiting {@link #ensureLeader()} calls and hands the change off. */
    private void onAnnounced(LeaderChange change) {
        synchronized (leaderMonitor) {
            leaderMonitor.notifyAll();
        }
        dispatch(() -> listeners.forEach(listener -> {
            try {
                listener.onLeaderChanged(change);
            } catch (RuntimeException e) {
                log.warn("A leader listener failed on {}", change, e);
            }
        }));
    }

    private void dispatch(Runnable task) {
        try {
            dispatcher.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.warn("A task on {} failed", DISPATCHER_THREAD, e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.debug("{} is closed; dropped a task", DISPATCHER_THREAD);
        }
    }
}
