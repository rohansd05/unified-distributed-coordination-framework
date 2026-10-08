package com.udcf.modules.replication;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.cluster.NodeService;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

/**
 * Experiment 5 transport, shared with Experiment 8: a node's "replication" service, a TCP
 * listener on 127.0.0.1:{@code ports().replication()} (710k) holding the node's replicated
 * {@link DataStore} (R2: TCP, because replication needs a definite acknowledgement and an
 * immediate error when a peer is gone).
 *
 * <p><b>Algorithm versus transport.</b> Every decision is made by the pure E5a classes: each
 * update, local or remote, goes through {@link DataStore#apply(DataItem, long)} with the
 * sender's epoch, and anti-entropy through {@link AntiEntropy}. This class only carries bytes
 * ({@link ReplicationProtocol}) and measures.</p>
 *
 * <p><b>Primary role and epochs.</b> A node acts as primary only after
 * {@link #becomePrimary(long)}; who should be primary is decided outside (Exp 5:
 * {@link ReplicaSet} with the module-local {@link ReplicationRoleSelector}; Exp 8: failover).
 * Every push carries the primary's epoch. The node stops acting as primary, and publishes
 * {@code PRIMARY_SUPERSEDED} once, as soon as it learns a higher epoch: from a
 * {@link ApplyResult#STALE_EPOCH} reply to its own push, from a push by a newer primary, or
 * through {@link #observeEpoch(long)}. The role survives a crash, so a recovered old primary
 * still believes it is primary until its first push teaches it otherwise.</p>
 *
 * <p><b>Writes.</b> {@link #write} stamps the item with {@code clock().tick()}, this node's id
 * and its primary epoch, applies it locally, then pushes it to every backup. SYNCHRONOUS pushes
 * to all backups in parallel and confirms after every reply or failure; ASYNCHRONOUS confirms at
 * once and pushes each backup after the <b>simulated</b> {@code async-delay-millis} (R7).</p>
 *
 * <p><b>Lamport time (link L4).</b> Every message carries the sender's {@code tick()}; the
 * receiver calls {@code update(senderTime)} on receipt and stamps its reply with {@code tick()};
 * the sender merges the reply's time. Every event carries a Lamport time from this node's
 * shared clock.</p>
 *
 * <p><b>Crashed nodes refuse to serve.</b> A crash closes the listener, so peers get a real
 * refusal; the in-process reads {@link #get} and {@link #snapshot()} throw
 * {@link NodeDownException}. The store, the role and the statistics survive crash and recovery
 * (stable storage, as in the legacy demo).</p>
 *
 * <p><b>Closing.</b> {@link #crash()} and {@link #stop()} return only after the accept thread
 * has exited (so the port is really closed: on Linux a listener closed while another thread is
 * blocked in {@code accept()} keeps listening until that thread wakes), after every connection
 * handler has finished (a handler re-checks {@code running} immediately before it touches the
 * store and replies nothing once the node is down, so no request changes the store after
 * {@code crash()} returns), and after every outbound push and the async scheduler have stopped.
 * Each wait is bounded by {@value #EXIT_TIMEOUT_SECONDS} s; if one does not finish, an
 * {@link IllegalStateException} is thrown. Pending asynchronous pushes are dropped
 * ({@link PushStatus#NOT_SENT}) and a crash reports them as {@code ASYNC_PUSHES_DROPPED}.</p>
 *
 * <p><b>Inbound limits.</b> Every accepted connection gets {@code SO_TIMEOUT = timeout-millis};
 * lines are bounded ({@value ReplicationProtocol#MAX_LINE_LENGTH} characters) and messages hold
 * at most {@value ReplicationProtocol#MAX_ITEMS_PER_MESSAGE} items. Anything malformed gets an
 * ERROR reply and changes nothing.</p>
 *
 * <p><b>Events</b> (module {@value #MODULE}, this node): {@code WRITE}, {@code WRITE_CONFIRMED},
 * {@code WRITE_NOT_CONFIRMED}, {@code ACK}, {@code REPLICATION_FAILED}, {@code REPLICA_APPLIED},
 * {@code REPLICA_DUPLICATE}, {@code REPLICA_STALE}, {@code REPLICA_STALE_EPOCH},
 * {@code ANTI_ENTROPY}, {@code ANTI_ENTROPY_FAILED}, {@code ANTI_ENTROPY_MERGED},
 * {@code CATCH_UP}, {@code CATCH_UP_FAILED},
 * {@code PRIMARY_ACTIVE}, {@code PRIMARY_STEPPED_DOWN}, {@code PRIMARY_SUPERSEDED},
 * {@code ASYNC_PUSHES_DROPPED}; their data keys are listed in docs/tracks/track-c-jai.md.
 * Reads and dumps publish nothing.</p>
 *
 * <p>No meters here: replication metrics arrive with the module in E5c.</p>
 */
public class ReplicationNodeService implements NodeService {

    public static final String NAME = "replication";
    public static final String MODULE = "replication";

    private static final Logger log = LoggerFactory.getLogger(ReplicationNodeService.class);

    static final long EXIT_TIMEOUT_SECONDS = 5;

    /**
     * How long crash() and stop() wait for each thread or executor to finish. A shutdown bound,
     * not deployment config: reaching it means the port may still be listening or a handler is
     * stuck.
     */
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(EXIT_TIMEOUT_SECONDS);

    /** Most input read and dropped after a malformed request (bounded further by SO_TIMEOUT). */
    private static final long MAX_DISCARD_BYTES = 4L * 1024 * 1024;

    /** Most keys listed in one ASYNC_PUSHES_DROPPED event. */
    private static final int MAX_DROPPED_KEYS_LISTED = 20;

    /** Test seam: lets a test pause a handler after it read a request. Production uses {@link #NO_PROBE}. */
    interface Probe {
        void afterRequestRead(ReplicationProtocol.Request request);
    }

    static final Probe NO_PROBE = request -> {
    };

    private final ClusterNode node;
    private final IntUnaryOperator peerPort;
    private final ReplicationProperties properties;
    private final ClusterEventBus bus;
    private final Clock wallClock;
    private final Probe probe;
    private final ReplicationClient client;
    private final DataStore store = new DataStore();
    private final Map<Integer, ReplicationStats> stats = new ConcurrentHashMap<>();
    private final AtomicLong primaryEpoch = new AtomicLong();   // 0 = not acting as primary
    private final ReentrantLock roleLock = new ReentrantLock();
    private final Set<Socket> inbound = ConcurrentHashMap.newKeySet();
    private final Set<Socket> outbound = ConcurrentHashMap.newKeySet();
    private final Set<PendingPush> pending = ConcurrentHashMap.newKeySet();

    private volatile Engine engine;       // the current generation of executors; null before the first start
    private volatile ServerSocket server;
    private volatile boolean running;
    private Thread acceptThread;          // guarded by this: only the synchronized lifecycle methods touch it

    /** One generation of the threads that serve and send; rebuilt by every start and recover. */
    private record Engine(ExecutorService handlers, ExecutorService pushes, ScheduledThreadPoolExecutor scheduler,
                          List<Thread> schedulerThreads) {
    }

    /** An asynchronous push waiting for its simulated delay. Identity equality on purpose. */
    private static final class PendingPush {
        private final int backupId;
        private final DataItem item;
        private final long senderEpoch;
        private final CompletableFuture<PushOutcome> future = new CompletableFuture<>();

        private PendingPush(int backupId, DataItem item, long senderEpoch) {
            this.backupId = backupId;
            this.item = item;
            this.senderEpoch = senderEpoch;
        }
    }

    /**
     * @param peerPort  the replication port of another node, by id
     * @param wallClock wall time for {@link ReplicationStats} "last sync" only (display); never
     *                  used for ordering
     */
    public ReplicationNodeService(ClusterNode node, IntUnaryOperator peerPort, ReplicationProperties properties,
                                  ClusterEventBus bus, Clock wallClock) {
        this(node, peerPort, properties, bus, wallClock, NO_PROBE);
    }

    ReplicationNodeService(ClusterNode node, IntUnaryOperator peerPort, ReplicationProperties properties,
                           ClusterEventBus bus, Clock wallClock, Probe probe) {
        this.node = Objects.requireNonNull(node, "node must not be null");
        this.peerPort = Objects.requireNonNull(peerPort, "peerPort must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock must not be null");
        this.probe = Objects.requireNonNull(probe, "probe must not be null");
        this.client = new ReplicationClient(properties.timeoutMillis(), new OutboundGuard());
    }

    /**
     * The node's replication service, created and started on first use through
     * {@link ClusterNode#ensureService} (so a crashed node throws {@link NodeDownException}).
     * Peer ports come from {@code cluster.node(id).ports().replication()}.
     */
    public static ReplicationNodeService on(ClusterNode node, Cluster cluster, ReplicationProperties properties,
                                            ClusterEventBus bus) {
        Objects.requireNonNull(cluster, "cluster must not be null");
        return node.ensureService(NAME, n -> new ReplicationNodeService(n,
                peerId -> cluster.node(peerId).ports().replication(), properties, bus, Clock.systemUTC()));
    }

    /** The node's replication service if it has ever been started; never starts one. */
    public static Optional<ReplicationNodeService> find(ClusterNode node) {
        return node.service(NAME).map(ReplicationNodeService.class::cast);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public synchronized void start() {
        open();
    }

    @Override
    public synchronized void crash() {
        close(true);
    }

    @Override
    public synchronized void recover() {
        open();
    }

    @Override
    public synchronized void stop() {
        close(false);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * A module reset: back to a fresh service. A running service is closed first (the close
     * rule: accept thread, handlers, pushes and scheduler all finished; pending asynchronous
     * pushes end {@link PushStatus#NOT_SENT}), then its store (epoch back to 1), statistics and
     * role are cleared, and it is reopened on the same port. A service that is not running (its
     * node is crashed) is only cleared and stays closed: whether the node recovers is the
     * cluster's decision. Publishes nothing.
     */
    public synchronized void resetState() {
        boolean wasRunning = running;
        if (wasRunning) {
            close(false);
        }
        store.clear();
        stats.clear();
        primaryEpoch.set(0);
        if (wasRunning) {
            open();
        }
    }

    public int nodeId() {
        return node.id();
    }

    public int port() {
        return node.ports().replication();
    }

    // ------------------------------------------------------------------ role and epoch

    /**
     * Acts as primary at {@code epoch}: raises the store's epoch to it first, so pushes carry it
     * and backups learn it from the first one. Calling it again with the same epoch changes
     * nothing. Exp 5 passes the current {@link #epoch()}; Exp 8 passes a new, higher one.
     *
     * @throws NodeDownException    if the node is down
     * @throws NotPrimaryException  if the node already knows a higher epoch
     */
    public void becomePrimary(long epoch) {
        requireRunning();
        if (epoch < DataStore.INITIAL_EPOCH) {
            throw new IllegalArgumentException("epoch must be >= " + DataStore.INITIAL_EPOCH + ", was " + epoch);
        }
        roleLock.lock();
        try {
            long known = store.epoch();
            if (epoch < known) {
                throw new NotPrimaryException(node.id(), known, "Node " + node.id() + " cannot become primary at epoch "
                        + epoch + ": it already knows epoch " + known);
            }
            store.observeEpoch(epoch);
            long previous = primaryEpoch.getAndSet(epoch);
            if (previous != epoch) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("epoch", epoch);
                data.put("previousEpoch", previous);
                publish("PRIMARY_ACTIVE", node.clock().tick(), null,
                        "Node " + node.id() + " acts as replication primary at epoch " + epoch, data);
            }
        } finally {
            roleLock.unlock();
        }
        noteEpoch(store.epoch(), null);   // a push from a newer primary may have raised it meanwhile
    }

    /** Stops acting as primary (for example because another node was selected). Idempotent. */
    public void stepDown() {
        roleLock.lock();
        try {
            long previous = primaryEpoch.getAndSet(0);
            if (previous != 0) {
                publish("PRIMARY_STEPPED_DOWN", node.clock().tick(), null,
                        "Node " + node.id() + " stepped down as replication primary", Map.of("epoch", previous));
            }
        } finally {
            roleLock.unlock();
        }
    }

    /** True while this node acts as primary. Works on a crashed node too (the role survives a crash). */
    public boolean isPrimary() {
        return primaryEpoch.get() != 0;
    }

    /** The epoch this node acts as primary at, or empty if it does not. */
    public OptionalLong primaryEpoch() {
        long epoch = primaryEpoch.get();
        return epoch == 0 ? OptionalLong.empty() : OptionalLong.of(epoch);
    }

    /** The store's epoch: the highest epoch this node knows. Works on a crashed node too. */
    public long epoch() {
        return store.epoch();
    }

    /**
     * Raises the store's epoch to an epoch seen elsewhere (for example from a peer query in
     * Exp 8); a primary on a lower epoch is superseded. Never lowers it.
     *
     * @return the store's epoch after the call
     */
    public long observeEpoch(long observed) {
        long after = store.observeEpoch(observed);
        noteEpoch(after, null);
        return after;
    }

    /** Steps down, once, if the store now knows an epoch above the one this node is primary at. */
    private void noteEpoch(long storeEpoch, Integer learnedFrom) {
        long previous = primaryEpoch.getAndUpdate(p -> p != 0 && storeEpoch > p ? 0 : p);
        if (previous != 0 && storeEpoch > previous) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("previousEpoch", previous);
            data.put("newEpoch", storeEpoch);
            data.put("learnedFrom", learnedFrom == null ? "local" : "node " + learnedFrom);
            publish("PRIMARY_SUPERSEDED", node.clock().tick(), learnedFrom, "Node " + node.id()
                    + " learned epoch " + storeEpoch + " and stopped acting as primary (was " + previous + ")", data);
        }
    }

    // ------------------------------------------------------------------ writes and pushes

    /**
     * A client write on this node, which must be acting as primary.
     *
     * <p>SYNCHRONOUS returns after every backup replied or failed. If that round taught the
     * node a higher epoch, the write is <b>not confirmed</b> and {@link NotPrimaryException} is
     * thrown; "not confirmed" does <b>not</b> mean "not stored": the item was already applied to
     * this node's own store, and possibly to backups that had not yet seen the newer epoch.
     * ASYNCHRONOUS returns at once; its pushes follow after the <b>simulated</b> delay, and a
     * supersession then shows only in the outcomes and events.</p>
     *
     * @param backupIds the backups to push to: distinct, other nodes of the cluster, crashed ones included
     * @throws NodeDownException       if the node is down, or went down before a synchronous write was confirmed
     * @throws NotPrimaryException     if the node is not acting as primary, or was superseded (see above)
     * @throws IllegalArgumentException for an invalid key, value or backup list
     */
    public WriteResult write(String key, String value, ConsistencyModel model, Collection<Integer> backupIds) {
        long startNanos = System.nanoTime();
        Objects.requireNonNull(model, "model must not be null");
        List<Integer> backups = validBackups(backupIds);
        Engine current = requireRunning();
        long epoch = primaryEpoch.get();
        if (epoch == 0) {
            throw new NotPrimaryException(node.id(), store.epoch(), "Node " + node.id() + " is not acting as primary");
        }
        new DataItem(key, value, 0, node.id(), epoch);   // validates the input before the clock ticks
        DataItem item = new DataItem(key, value, node.clock().tick(), node.id(), epoch);
        ApplyResult local = store.apply(item, epoch);
        if (local == ApplyResult.STALE_EPOCH) {
            noteEpoch(store.epoch(), null);
            throw new NotPrimaryException(node.id(), store.epoch(), "Node " + node.id() + " was superseded by epoch "
                    + store.epoch() + " before writing '" + key + "'; nothing was stored");
        }
        Map<String, Object> writeData = new LinkedHashMap<>();
        writeData.put("key", key);
        writeData.put("value", value);
        writeData.put("model", model.name());
        writeData.put("epoch", epoch);
        writeData.put("localResult", local.name());
        writeData.put("backups", backups);
        publish("WRITE", item.lamportTime(), null, "Client write of '" + key + "' [" + model + "]", writeData);

        if (model == ConsistencyModel.SYNCHRONOUS) {
            return writeSynchronously(current, item, local, backups, startNanos);
        }
        return writeAsynchronously(current, item, local, backups, startNanos);
    }

    private WriteResult writeSynchronously(Engine current, DataItem item, ApplyResult local, List<Integer> backups,
                                           long startNanos) {
        List<CompletableFuture<PushOutcome>> futures = new ArrayList<>();
        for (int backupId : backups) {
            futures.add(submit(current, () -> push(backupId, item, item.epoch(), 0, false),
                    () -> PushOutcome.withoutReply(backupId, PushStatus.NOT_SENT, downDetail())));
        }
        List<PushOutcome> outcomes = futures.stream().map(CompletableFuture::join).toList();
        if (!running) {
            writeNotConfirmed(item, "node went down during the synchronous round");
            throw new NodeDownException(node.id());
        }
        boolean superseded = primaryEpoch.get() != item.epoch()
                || outcomes.stream().anyMatch(o -> o.result().equals(Optional.of(ApplyResult.STALE_EPOCH)));
        if (superseded) {
            writeNotConfirmed(item, "superseded by epoch " + store.epoch());
            throw new NotPrimaryException(node.id(), store.epoch(), "Node " + node.id() + " was superseded by epoch "
                    + store.epoch() + " during the synchronous write of '" + item.key() + "': the write is NOT"
                    + " confirmed, but the item was already applied to this node's own store and possibly to some"
                    + " backups");
        }
        double confirmMillis = millisSince(startNanos);
        writeConfirmed(item, ConsistencyModel.SYNCHRONOUS, confirmMillis, 0, outcomes);
        return new WriteResult(item, ConsistencyModel.SYNCHRONOUS, local, confirmMillis, 0, backups,
                CompletableFuture.completedFuture(outcomes));
    }

    private WriteResult writeAsynchronously(Engine current, DataItem item, ApplyResult local, List<Integer> backups,
                                            long startNanos) {
        long delay = properties.asyncDelayMillis();   // SIMULATED network latency (R7)
        List<CompletableFuture<PushOutcome>> futures = new ArrayList<>();
        for (int backupId : backups) {
            futures.add(schedule(current, new PendingPush(backupId, item, item.epoch()), delay));
        }
        CompletableFuture<List<PushOutcome>> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
        double confirmMillis = millisSince(startNanos);
        writeConfirmed(item, ConsistencyModel.ASYNCHRONOUS, confirmMillis, delay, List.of());
        return new WriteResult(item, ConsistencyModel.ASYNCHRONOUS, local, confirmMillis, delay, backups, all);
    }

    private void writeConfirmed(DataItem item, ConsistencyModel model, double confirmMillis, long delay,
                                List<PushOutcome> outcomes) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", item.key());
        data.put("model", model.name());
        data.put("confirmMillis", confirmMillis);
        if (model == ConsistencyModel.SYNCHRONOUS) {
            data.put("acked", outcomes.stream().filter(PushOutcome::acknowledged).count());
            data.put("failed", outcomes.stream().filter(o -> o.status() == PushStatus.FAILED).count());
        } else {
            data.put("simulated", true);
            data.put("simulatedDelayMillis", delay);
        }
        publish("WRITE_CONFIRMED", node.clock().tick(), null, String.format(Locale.ROOT,
                "Write of '%s' confirmed to the client after %.1f ms [%s]", item.key(), confirmMillis, model), data);
    }

    private void writeNotConfirmed(DataItem item, String reason) {
        if (!running) {
            return;   // a dead node publishes nothing
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", item.key());
        data.put("reason", reason);
        data.put("storedLocally", true);
        publish("WRITE_NOT_CONFIRMED", node.clock().tick(), null,
                "Write of '" + item.key() + "' NOT confirmed: " + reason, data);
    }

    /**
     * Delivers a pre-built item straight to one backup, bypassing the write path. Exists only so
     * a demonstration can deliver an old update after a newer one and show the backup refusing
     * it ({@link OutOfOrderInjector}). Sent with this node's store epoch.
     *
     * @throws NodeDownException        if the node is down
     * @throws IllegalArgumentException if the item's epoch is above this node's store epoch
     */
    public PushOutcome deliverOutOfOrder(int backupId, DataItem staleItem) {
        Objects.requireNonNull(staleItem, "staleItem must not be null");
        validBackups(List.of(backupId));
        Engine current = requireRunning();
        long senderEpoch = store.epoch();
        if (staleItem.epoch() > senderEpoch) {
            throw new IllegalArgumentException("item epoch " + staleItem.epoch() + " is above this node's epoch "
                    + senderEpoch);
        }
        return submit(current, () -> push(backupId, staleItem, senderEpoch, 0, true), () -> {
            throw new NodeDownException(node.id());
        }).join();
    }

    /** Never throws: every way a push can end is a {@link PushOutcome}. */
    private PushOutcome push(int backupId, DataItem item, long senderEpoch, long simulatedDelay, boolean outOfOrder) {
        ReplicationStats backupStats = stats.computeIfAbsent(backupId, ReplicationStats::new);
        long start = System.nanoTime();
        try {
            AckReply ack = client.replicate(peerPort.applyAsInt(backupId), node.id(), node.clock(), senderEpoch, item);
            double latency = millisSince(start);
            backupStats.recordAck(ack.result(), latency, Instant.now(wallClock));
            if (ack.result() == ApplyResult.STALE_EPOCH) {
                noteEpoch(store.observeEpoch(ack.storeEpoch()), backupId);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("key", item.key());
            data.put("itemLamport", item.lamportTime());
            data.put("result", ack.result().name());
            data.put("senderEpoch", senderEpoch);
            data.put("backupEpoch", ack.storeEpoch());
            data.put("latencyMillis", latency);
            if (simulatedDelay > 0) {
                data.put("simulated", true);
                data.put("simulatedDelayMillis", simulatedDelay);
            }
            if (outOfOrder) {
                data.put("outOfOrder", true);
            }
            publish("ACK", node.clock().tick(), backupId, String.format(Locale.ROOT,
                    "Node %d answered %s for '%s' in %.1f ms", backupId, ack.result(), item.key(), latency), data);
            return PushOutcome.acked(backupId, ack.result(), ack.storeEpoch(), latency);
        } catch (ReplicationClient.SenderDownException e) {
            return PushOutcome.withoutReply(backupId, PushStatus.NOT_SENT, e.getMessage());
        } catch (IOException e) {
            if (!running) {
                return PushOutcome.withoutReply(backupId, PushStatus.ABANDONED,
                        "Node " + node.id() + " went down while the push was in flight: " + describe(e));
            }
            backupStats.recordFailure();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("key", item.key());
            data.put("reason", e.getClass().getSimpleName());
            data.put("message", String.valueOf(e.getMessage()));
            publish("REPLICATION_FAILED", node.clock().tick(), backupId,
                    "Replication of '" + item.key() + "' to node " + backupId + " failed: " + describe(e), data);
            return PushOutcome.withoutReply(backupId, PushStatus.FAILED, describe(e));
        }
    }

    private CompletableFuture<PushOutcome> schedule(Engine current, PendingPush push, long delayMillis) {
        pending.add(push);
        try {
            current.scheduler().schedule(() -> fire(current, push, delayMillis), delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Shut down by a crash: the check below drops it.
        }
        // A crash between requireRunning() and add() would have missed this push.
        if ((!running || current != engine) && pending.remove(push)) {
            push.future.complete(PushOutcome.withoutReply(push.backupId, PushStatus.NOT_SENT, downDetail()));
        }
        return push.future;
    }

    private void fire(Engine current, PendingPush push, long delayMillis) {
        if (!pending.remove(push)) {
            return;   // already dropped by a crash
        }
        CompletableFuture<PushOutcome> sent = submit(current,
                () -> push(push.backupId, push.item, push.senderEpoch, delayMillis, false),
                () -> PushOutcome.withoutReply(push.backupId, PushStatus.NOT_SENT, downDetail()));
        sent.whenComplete((outcome, error) -> {
            if (error == null) {
                push.future.complete(outcome);
            } else {
                push.future.completeExceptionally(error);
            }
        });
    }

    /**
     * Runs outbound work on the current generation, so crash() waits for it. If the generation
     * is already shut down, returns {@code whenDown}'s result, or lets its exception through.
     */
    private <T> CompletableFuture<T> submit(Engine current, Supplier<T> work, Supplier<T> whenDown) {
        try {
            return CompletableFuture.supplyAsync(work, current.pushes());
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(whenDown.get());
        }
    }

    // ------------------------------------------------------------------ anti-entropy

    /**
     * Anti-entropy: pushes this node's whole store to {@code targetId}, in chunks of
     * {@code batch-size} items (at least one SYNC message, so an empty store still proves the
     * target reachable), with this node's store epoch. Stops at the first failed chunk. Not
     * counted in {@link ReplicationStats}.
     *
     * @throws NodeDownException if this node is down
     */
    public AntiEntropyReport antiEntropy(int targetId) {
        validBackups(List.of(targetId));
        Engine current = requireRunning();
        int port = peerPort.applyAsInt(targetId);
        return submit(current, () -> runAntiEntropy(targetId, port), () -> {
            throw new NodeDownException(node.id());
        }).join();
    }

    private AntiEntropyReport runAntiEntropy(int targetId, int port) {
        long senderEpoch = store.epoch();
        List<DataItem> plan = AntiEntropy.plan(store);
        List<List<DataItem>> chunks = new ArrayList<>();
        for (int from = 0; from < plan.size(); from += properties.batchSize()) {
            chunks.add(plan.subList(from, Math.min(plan.size(), from + properties.batchSize())));
        }
        if (chunks.isEmpty()) {
            chunks.add(List.of());
        }
        long start = System.nanoTime();
        int pushed = 0;
        int applied = 0;
        int alreadyCurrent = 0;
        int stale = 0;
        int staleEpoch = 0;
        int acknowledged = 0;
        Optional<String> failure = Optional.empty();
        for (List<DataItem> chunk : chunks) {
            try {
                SyncReply reply = client.sync(port, node.id(), node.clock(), senderEpoch, chunk);
                AntiEntropyResult r = reply.result();
                pushed += r.pushed();
                applied += r.applied();
                alreadyCurrent += r.alreadyCurrent();
                stale += r.stale();
                staleEpoch += r.staleEpoch();
                acknowledged++;
                if (r.staleEpoch() > 0) {
                    noteEpoch(store.observeEpoch(reply.storeEpoch()), targetId);
                }
            } catch (IOException e) {
                failure = Optional.of(describe(e));
                break;
            }
        }
        AntiEntropyReport report = new AntiEntropyReport(targetId,
                new AntiEntropyResult(pushed, applied, alreadyCurrent, stale, staleEpoch), chunks.size(), acknowledged,
                millisSince(start), failure);
        if (running) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pushed", pushed);
            data.put("applied", applied);
            data.put("alreadyCurrent", alreadyCurrent);
            data.put("stale", stale);
            data.put("staleEpoch", staleEpoch);
            data.put("chunksPlanned", chunks.size());
            data.put("chunksAcknowledged", acknowledged);
            data.put("latencyMillis", report.latencyMillis());
            failure.ifPresent(reason -> data.put("reason", reason));
            publish(report.completed() ? "ANTI_ENTROPY" : "ANTI_ENTROPY_FAILED", node.clock().tick(), targetId,
                    report.completed()
                            ? "Anti-entropy to node " + targetId + ": " + applied + " of " + pushed + " items applied"
                            : "Anti-entropy to node " + targetId + " failed: " + failure.get(), data);
        }
        return report;
    }

    // ------------------------------------------------------------------ catch-up (pull)

    /**
     * Catch-up, the reverse of anti-entropy: pulls {@code sourceId}'s whole store over TCP
     * (DUMP pages of {@code batch-size}) and merges it into this node's store with
     * {@link AntiEntropy#merge}, using the source's store epoch as the sender epoch. A higher
     * epoch is learned (and a primary on a lower one is superseded); old-epoch items compete by
     * last-writer-wins. Used by a node about to take over as primary, which has missed writes.
     *
     * <p><b>Lamport time.</b> Each DUMP page ticks this node's clock before sending and merges
     * the reply's time, and a replica's clock is always at or above the Lamport time of every
     * item it holds; so after a successful catch-up this node's clock is above every item it
     * pulled, and its next write wins against all of them.</p>
     *
     * <p>Runs on the push executor, so {@code crash()} waits for it; nothing is merged once
     * this node is down. Publishes {@code CATCH_UP} or {@code CATCH_UP_FAILED}.</p>
     *
     * @throws NodeDownException if this node is down
     */
    public CatchUpReport catchUpFrom(int sourceId) {
        validBackups(List.of(sourceId));
        Engine current = requireRunning();
        int port = peerPort.applyAsInt(sourceId);
        return submit(current, () -> runCatchUp(sourceId, port), () -> {
            throw new NodeDownException(node.id());
        }).join();
    }

    private CatchUpReport runCatchUp(int sourceId, int port) {
        long start = System.nanoTime();
        CatchUpReport report;
        try {
            ReplicaDump dump = client.dump(port, node.id(), node.clock(), properties.batchSize());
            if (!running) {
                return CatchUpReport.failed(sourceId, millisSince(start), downDetail());
            }
            AntiEntropyResult merged = AntiEntropy.merge(store, dump.items().values(), dump.storeEpoch());
            noteEpoch(store.epoch(), sourceId);
            report = CatchUpReport.merged(sourceId, merged, dump.storeEpoch(), millisSince(start));
        } catch (IOException e) {
            report = CatchUpReport.failed(sourceId, millisSince(start), describe(e));
        }
        if (running) {
            Map<String, Object> data = new LinkedHashMap<>();
            report.merged().ifPresent(r -> {
                data.put("pulled", r.pushed());
                data.put("applied", r.applied());
                data.put("alreadyCurrent", r.alreadyCurrent());
                data.put("stale", r.stale());
                data.put("staleEpoch", r.staleEpoch());
            });
            report.sourceEpoch().ifPresent(epoch -> data.put("sourceEpoch", epoch));
            data.put("storeEpoch", store.epoch());
            data.put("latencyMillis", report.latencyMillis());
            report.failure().ifPresent(reason -> data.put("reason", reason));
            publish(report.completed() ? "CATCH_UP" : "CATCH_UP_FAILED", node.clock().tick(), sourceId,
                    report.completed()
                            ? "Node " + node.id() + " caught up from node " + sourceId + ": "
                            + report.merged().get().applied() + " of " + report.merged().get().pushed() + " items applied"
                            : "Node " + node.id() + " could not catch up from node " + sourceId + ": "
                            + report.failure().get(), data);
        }
        return report;
    }

    // ------------------------------------------------------------------ in-process reads and stats

    /** The version of {@code key} this node holds. @throws NodeDownException if the node is down */
    public Optional<DataItem> get(String key) {
        requireRunning();
        return store.get(key);
    }

    /** This node's store, detached and in key order. @throws NodeDownException if the node is down */
    public SortedMap<String, DataItem> snapshot() {
        requireRunning();
        return store.snapshot();
    }

    /** Per-backup push statistics, by backup id; kept across crash and recovery. */
    public SortedMap<Integer, ReplicationStatsSnapshot> stats() {
        TreeMap<Integer, ReplicationStatsSnapshot> result = new TreeMap<>();
        stats.forEach((backupId, backupStats) -> result.put(backupId, backupStats.snapshot()));
        return Collections.unmodifiableSortedMap(result);
    }

    public void resetStats() {
        stats.values().forEach(ReplicationStats::reset);
    }

    // ------------------------------------------------------------------ server side

    private void open() {
        if (running) {
            return;
        }
        ServerSocket socket;
        try {
            socket = bind();
        } catch (IOException e) {
            throw new UncheckedIOException("Node " + node.id() + " cannot bind replication port " + port(), e);
        }
        String prefix = "udcf-replication-n" + node.id();
        List<Thread> schedulerThreads = new CopyOnWriteArrayList<>();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, prefix + "-async");
            thread.setDaemon(true);
            schedulerThreads.add(thread);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        Engine fresh = new Engine(
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(prefix + "-conn-", 0).factory()),
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(prefix + "-push-", 0).factory()),
                scheduler, schedulerThreads);
        engine = fresh;
        server = socket;
        running = true;
        Thread accept = new Thread(() -> acceptLoop(socket, fresh), prefix + "-accept");
        accept.setDaemon(true);
        acceptThread = accept;
        accept.start();
    }

    private ServerSocket bind() throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);   // as RequestsNodeService: immediate rebind after a crash
            socket.bind(new InetSocketAddress(ReplicationProtocol.LOOPBACK, port()));
            return socket;
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    private void close(boolean crashed) {
        running = false;
        closeQuietly(server);
        inbound.forEach(ReplicationNodeService::closeQuietly);
        outbound.forEach(ReplicationNodeService::closeQuietly);
        Engine current = engine;
        if (current != null) {
            current.scheduler().shutdownNow();
            dropPending(crashed);
            current.pushes().shutdownNow();
            // No interrupt: a handler re-checks `running` before it touches the store.
            current.handlers().shutdown();
        }
        IllegalStateException failure = null;
        failure = collect(failure, this::awaitAcceptThreadExit);
        if (current != null) {
            failure = collect(failure, () -> awaitTermination(current.handlers(), "connection handlers"));
            failure = collect(failure, () -> awaitTermination(current.pushes(), "outbound pushes"));
            failure = collect(failure, () -> awaitTermination(current.scheduler(), "async scheduler"));
            for (Thread thread : current.schedulerThreads()) {
                failure = collect(failure, () -> join(thread));
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void dropPending(boolean crashed) {
        int dropped = 0;
        Set<String> keys = new LinkedHashSet<>();
        for (PendingPush push : pending) {
            if (pending.remove(push)) {
                push.future.complete(PushOutcome.withoutReply(push.backupId, PushStatus.NOT_SENT,
                        "Node " + node.id() + " went down before this asynchronous push was sent"));
                dropped++;
                keys.add(push.item.key());
            }
        }
        if (crashed && dropped > 0) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pushes", dropped);
            data.put("keys", keys.stream().limit(MAX_DROPPED_KEYS_LISTED).toList());
            data.put("keysTruncated", keys.size() > MAX_DROPPED_KEYS_LISTED);
            publish("ASYNC_PUSHES_DROPPED", node.clock().tick(), null,
                    dropped + " asynchronous push(es) were still waiting when node " + node.id() + " crashed: lost in flight",
                    data);
        }
    }

    private interface Wait {
        void run();
    }

    private static IllegalStateException collect(IllegalStateException first, Wait wait) {
        try {
            wait.run();
            return first;
        } catch (IllegalStateException e) {
            if (first == null) {
                return e;
            }
            first.addSuppressed(e);
            return first;
        }
    }

    /**
     * Waits, bounded, for the accept thread to exit: only then is the listening socket really
     * closed. The accept thread never takes this object's lock, so joining it while holding the
     * lock cannot deadlock.
     */
    private void awaitAcceptThreadExit() {
        Thread accept = acceptThread;
        if (accept == null) {
            return;
        }
        join(accept);
        acceptThread = null;
    }

    private void join(Thread thread) {
        try {
            if (!thread.join(EXIT_TIMEOUT)) {
                throw new IllegalStateException("Node " + node.id() + ": thread " + thread.getName()
                        + " did not exit within " + EXIT_TIMEOUT.toMillis() + " ms; port " + port()
                        + " may still be listening");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Node " + node.id() + ": interrupted while waiting for thread "
                    + thread.getName() + " to exit", e);
        }
    }

    private void awaitTermination(ExecutorService executor, String what) {
        try {
            if (!executor.awaitTermination(EXIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Node " + node.id() + ": " + what + " did not finish within "
                        + EXIT_TIMEOUT.toMillis() + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Node " + node.id() + ": interrupted while waiting for " + what, e);
        }
    }

    private void acceptLoop(ServerSocket socket, Engine current) {
        while (!socket.isClosed()) {
            Socket connection;
            try {
                connection = socket.accept();
            } catch (IOException e) {
                return;   // closed by crash() or stop(): expected
            }
            inbound.add(connection);
            // A crash between accept() and add() would have missed this connection.
            if (!running || socket.isClosed()) {
                closeQuietly(connection);
                inbound.remove(connection);
                return;
            }
            try {
                current.handlers().execute(() -> serve(connection));
            } catch (RejectedExecutionException e) {
                closeQuietly(connection);
                inbound.remove(connection);
                return;
            }
        }
    }

    private void serve(Socket connection) {
        try (connection) {
            connection.setSoTimeout(properties.timeoutMillis());
            InputStream in = new BufferedInputStream(connection.getInputStream());
            OutputStream out = new BufferedOutputStream(connection.getOutputStream());
            ReplicationProtocol.Request request;
            try {
                request = ReplicationProtocol.readRequest(in);
            } catch (ProtocolException malformed) {
                refuse(out, malformed.getMessage());
                discardRest(connection, in);
                return;
            }
            if (request == null) {
                return;
            }
            long receiveLamport = node.clock().update(request.lamportTime());   // Lamport rule 3
            probe.afterRequestRead(request);
            List<String> reply = switch (request.type()) {
                case REPLICATE -> handleReplicate(request, receiveLamport);
                case SYNC -> handleSync(request, receiveLamport);
                case READ -> handleRead(request);
                case DUMP -> handleDump(request);
            };
            if (reply != null) {
                ReplicationProtocol.writeLines(out, reply);
            }
        } catch (IOException e) {
            log.debug("Node {}: replication connection ended early: {}", node.id(), e.getMessage());
        } finally {
            inbound.remove(connection);
        }
    }

    /** @return the reply, or null if the node went down: a dead node sends nothing */
    private List<String> handleReplicate(ReplicationProtocol.Request request, long receiveLamport) {
        DataItem item = request.items().get(0);
        if (!running) {
            return null;   // re-checked immediately before the store changes
        }
        ApplyResult result = store.apply(item, request.senderEpoch());
        long storeEpoch = store.epoch();
        noteEpoch(storeEpoch, request.senderId());
        if (!running) {
            return null;
        }
        long replyLamport = node.clock().tick();                              // Lamport rule 2
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", item.key());
        data.put("itemLamport", item.lamportTime());
        data.put("originNode", item.originNode());
        data.put("itemEpoch", item.epoch());
        data.put("senderEpoch", request.senderEpoch());
        data.put("storeEpoch", storeEpoch);
        data.put("receiveLamport", receiveLamport);
        publish("REPLICA_" + result.name(), replyLamport, request.senderId(),
                "Node " + node.id() + " " + describe(result) + " '" + item.key() + "' from node " + request.senderId(), data);
        return List.of(ReplicationProtocol.ack(node.id(), replyLamport, storeEpoch, result));
    }

    private List<String> handleSync(ReplicationProtocol.Request request, long receiveLamport) {
        if (!running) {
            return null;   // re-checked immediately before the store changes
        }
        AntiEntropyResult result = AntiEntropy.merge(store, request.items(), request.senderEpoch());
        long storeEpoch = store.epoch();
        noteEpoch(storeEpoch, request.senderId());
        if (!running) {
            return null;
        }
        long replyLamport = node.clock().tick();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pushed", result.pushed());
        data.put("applied", result.applied());
        data.put("alreadyCurrent", result.alreadyCurrent());
        data.put("stale", result.stale());
        data.put("staleEpoch", result.staleEpoch());
        data.put("senderEpoch", request.senderEpoch());
        data.put("storeEpoch", storeEpoch);
        data.put("receiveLamport", receiveLamport);
        publish("ANTI_ENTROPY_MERGED", replyLamport, request.senderId(), "Node " + node.id() + " merged "
                + result.pushed() + " pushed items from node " + request.senderId() + ": " + result.applied()
                + " applied", data);
        return List.of(ReplicationProtocol.synced(node.id(), replyLamport, storeEpoch, result));
    }

    private List<String> handleRead(ReplicationProtocol.Request request) {
        if (!running) {
            return null;
        }
        Optional<DataItem> item = store.get(request.key());
        return ReplicationProtocol.value(node.id(), node.clock().tick(), store.epoch(), item);
    }

    private List<String> handleDump(ReplicationProtocol.Request request) {
        if (!running) {
            return null;
        }
        SortedMap<String, DataItem> all = store.snapshot();
        SortedMap<String, DataItem> after = request.key() == null ? all : all.tailMap(request.key());
        List<DataItem> page = new ArrayList<>(Math.min(request.limit(), after.size()));
        boolean more = false;
        for (DataItem item : after.values()) {
            if (item.key().equals(request.key())) {
                continue;   // tailMap is inclusive; the cursor key was in the previous page
            }
            if (page.size() == request.limit()) {
                more = true;
                break;
            }
            page.add(item);
        }
        return ReplicationProtocol.dumped(node.id(), node.clock().tick(), store.epoch(), page, more);
    }

    private void refuse(OutputStream out, String reason) throws IOException {
        log.warn("Node {} refused a replication request on port {}: {}", node.id(), port(), reason);
        ReplicationProtocol.writeLines(out, List.of(ReplicationProtocol.error(node.id(), node.clock().tick(), reason)));
    }

    /**
     * After refusing a malformed request: half-close, then read and drop what the client still
     * sends (bounded, and limited by SO_TIMEOUT). Closing with unread input would make TCP reset
     * the connection, which can destroy the ERROR reply before the client reads it.
     */
    private static void discardRest(Socket connection, InputStream in) throws IOException {
        connection.shutdownOutput();
        byte[] buffer = new byte[ReplicationProtocol.MAX_LINE_LENGTH];
        long discarded = 0;
        int read;
        while (discarded < MAX_DISCARD_BYTES && (read = in.read(buffer)) != -1) {
            discarded += read;
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Tracks this node's outbound sockets so a crash closes them, and refuses new ones while down. */
    private final class OutboundGuard implements ReplicationClient.SocketGuard {
        @Override
        public void opened(Socket socket) throws ReplicationClient.SenderDownException {
            outbound.add(socket);
            if (!running) {
                outbound.remove(socket);
                throw new ReplicationClient.SenderDownException(downDetail());
            }
        }

        @Override
        public void closed(Socket socket) {
            outbound.remove(socket);
        }
    }

    private Engine requireRunning() {
        Engine current = engine;
        if (!running || current == null) {
            throw new NodeDownException(node.id());
        }
        return current;
    }

    private List<Integer> validBackups(Collection<Integer> backupIds) {
        List<Integer> backups = List.copyOf(Objects.requireNonNull(backupIds, "backupIds must not be null"));
        if (new LinkedHashSet<>(backups).size() != backups.size()) {
            throw new IllegalArgumentException("backup ids must be distinct: " + backups);
        }
        for (int backupId : backups) {
            if (backupId == node.id()) {
                throw new IllegalArgumentException("node " + node.id() + " cannot replicate to itself");
            }
            peerPort.applyAsInt(backupId);   // an unknown node fails here, before anything changes
        }
        return backups;
    }

    private String downDetail() {
        return "Node " + node.id() + " is down, so nothing was sent";
    }

    private void publish(String type, long lamportTime, Integer peer, String message, Map<String, Object> data) {
        bus.publish(EventDraft.of(MODULE, node.id(), type, lamportTime).withPeer(peer).withMessage(message).withData(data));
    }

    private static String describe(ApplyResult result) {
        return switch (result) {
            case APPLIED -> "stored";
            case DUPLICATE -> "already held";
            case STALE -> "rejected as stale";
            case STALE_EPOCH -> "refused (stale epoch)";
        };
    }

    private static String describe(IOException e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static double millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000d;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            // Closing an already-closed socket is not worth reporting.
        }
    }
}
