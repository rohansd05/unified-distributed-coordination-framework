package com.udcf.core.failure;

import com.udcf.core.clock.LamportClock;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * The shared heartbeat failure detector of one node (link L2), used by election (Exp 4) and
 * fault tolerance (Exp 8) so both react to the same signal.
 *
 * <p>Every {@link #tick()} sends one heartbeat to every peer, then checks each peer's silence:
 * the time since its last heartbeat, or since {@link #start()} if none has arrived. A peer
 * silent for <b>more than</b> the timeout becomes suspected; a heartbeat from a suspected peer
 * makes it alive again. Only these state changes are published, never single heartbeats:
 * {@code PEER_SUSPECTED} and {@code PEER_ALIVE}, with the owner's module, this node's id, the
 * peer and {@code lamportTime = node.clock().tick()}. Each node has its own view, so a crashed
 * node is suspected separately by every live node.</p>
 *
 * <p><b>No threads of its own.</b> The owner schedules {@link #tick()} every interval and
 * feeds {@link #onHeartbeat(int)}; on the election channel both run on the node's election
 * worker. The detector knows nothing of the wire format: {@link HeartbeatSender} sends.</p>
 *
 * <p><b>Thread safety.</b> State changes happen under one lock; events are published and
 * listeners are called after it is released. A throwing sender or listener is logged and the
 * rest carry on.</p>
 */
public class FailureDetector {

    public static final String PEER_SUSPECTED = "PEER_SUSPECTED";
    public static final String PEER_ALIVE = "PEER_ALIVE";

    private static final Logger log = LoggerFactory.getLogger(FailureDetector.class);
    private static final long NANOS_PER_MILLI = 1_000_000L;

    /** Closing it removes the listener; closing twice does nothing. */
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private record Transition(int peerId, boolean suspected, long silentMillis) {
    }

    private static final class PeerRecord {
        Long lastHeardNanos;      // null until a heartbeat arrives after start()
        boolean suspected;
    }

    private final int nodeId;
    private final LamportClock clock;
    private final List<Integer> peers;
    private final FailureDetectorConfig config;
    private final String module;
    private final HeartbeatSender sender;
    private final ClusterEventBus bus;
    private final LongSupplier nanoClock;
    private final List<FailureListener> listeners = new CopyOnWriteArrayList<>();

    private final Object lock = new Object();
    private final Map<Integer, PeerRecord> records = new LinkedHashMap<>();   // guarded by lock
    private boolean active;                                                  // guarded by lock
    private long startedAtNanos;                                             // guarded by lock

    /**
     * @param peerIds every node of the cluster; this node's own id and duplicates are ignored
     * @param module  module id the events are published under (for example {@code "election"})
     */
    public FailureDetector(ClusterNode node, Collection<Integer> peerIds, FailureDetectorConfig config,
                           String module, HeartbeatSender sender, ClusterEventBus bus, LongSupplier nanoClock) {
        Objects.requireNonNull(node, "node must not be null");
        Objects.requireNonNull(peerIds, "peerIds must not be null");
        if (module == null || module.isBlank()) {
            throw new IllegalArgumentException("module must not be blank");
        }
        this.nodeId = node.id();
        this.clock = node.clock();
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.module = module;
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        Set<Integer> distinct = new LinkedHashSet<>(peerIds);
        distinct.remove(nodeId);
        this.peers = List.copyOf(distinct);
        peers.forEach(peer -> records.put(peer, new PeerRecord()));
    }

    public int nodeId() {
        return nodeId;
    }

    public FailureDetectorConfig config() {
        return config;
    }

    /** The peers this node watches, in the order given (never itself). */
    public List<Integer> peerIds() {
        return peers;
    }

    /**
     * Activates the detector with a fresh view: no peer suspected, none heard yet, and every
     * peer's silence counted from now (a grace period of one timeout). Publishes nothing.
     */
    public void start() {
        synchronized (lock) {
            active = true;
            startedAtNanos = nanoClock.getAsLong();
            records.values().forEach(record -> {
                record.lastHeardNanos = null;
                record.suspected = false;
            });
        }
    }

    /** Deactivates: {@link #tick()} and {@link #onHeartbeat(int)} do nothing until restarted. */
    public void stop() {
        synchronized (lock) {
            active = false;
        }
    }

    public boolean isActive() {
        synchronized (lock) {
            return active;
        }
    }

    /** Sends a heartbeat to every peer, then suspects every peer silent for longer than the timeout. */
    public void tick() {
        if (!isActive()) {
            return;
        }
        for (int peer : peers) {
            try {
                sender.sendHeartbeat(peer);
            } catch (RuntimeException e) {
                log.warn("Node {}: heartbeat to node {} failed: {}", nodeId, peer, e.toString());
            }
        }
        List<Transition> transitions = new ArrayList<>();
        synchronized (lock) {
            if (!active) {
                return;
            }
            long now = nanoClock.getAsLong();
            long timeoutNanos = config.timeoutMillis() * NANOS_PER_MILLI;
            for (Map.Entry<Integer, PeerRecord> entry : records.entrySet()) {
                PeerRecord record = entry.getValue();
                long silentNanos = now - silentSince(record);
                if (!record.suspected && silentNanos > timeoutNanos) {
                    record.suspected = true;
                    transitions.add(new Transition(entry.getKey(), true, silentNanos / NANOS_PER_MILLI));
                }
            }
        }
        transitions.forEach(this::announce);
    }

    /** Records a heartbeat from {@code peerId}; a suspected peer becomes alive. Unknown peers are ignored. */
    public void onHeartbeat(int peerId) {
        Transition transition = null;
        synchronized (lock) {
            PeerRecord record = records.get(peerId);
            if (!active || record == null) {
                return;
            }
            long now = nanoClock.getAsLong();
            if (record.suspected) {
                record.suspected = false;
                transition = new Transition(peerId, false, (now - silentSince(record)) / NANOS_PER_MILLI);
            }
            record.lastHeardNanos = now;
        }
        if (transition != null) {
            announce(transition);
        }
    }

    public boolean isSuspected(int peerId) {
        synchronized (lock) {
            PeerRecord record = records.get(peerId);
            return record != null && record.suspected;
        }
    }

    /** Currently suspected peers, in peer order. */
    public Set<Integer> suspectedPeers() {
        synchronized (lock) {
            Set<Integer> suspected = new LinkedHashSet<>();
            records.forEach((peer, record) -> {
                if (record.suspected) {
                    suspected.add(peer);
                }
            });
            return Set.copyOf(suspected);
        }
    }

    /** A snapshot of every peer, in peer order. */
    public List<PeerHealth> peers() {
        synchronized (lock) {
            long now = nanoClock.getAsLong();
            List<PeerHealth> health = new ArrayList<>(records.size());
            records.forEach((peer, record) -> health.add(new PeerHealth(peer, record.suspected,
                    record.lastHeardNanos == null ? null : (now - record.lastHeardNanos) / NANOS_PER_MILLI)));
            return List.copyOf(health);
        }
    }

    public Registration addListener(FailureListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
        AtomicBoolean registered = new AtomicBoolean(true);
        return () -> {
            if (registered.compareAndSet(true, false)) {
                listeners.remove(listener);
            }
        };
    }

    private long silentSince(PeerRecord record) {
        return record.lastHeardNanos != null ? record.lastHeardNanos : startedAtNanos;
    }

    private void announce(Transition transition) {
        int peer = transition.peerId();
        long silent = transition.silentMillis();
        String type = transition.suspected() ? PEER_SUSPECTED : PEER_ALIVE;
        String message = transition.suspected()
                ? "Node " + nodeId + " suspects node " + peer + ": no heartbeat for " + silent + " ms"
                : "Node " + nodeId + " hears node " + peer + " again after " + silent + " ms";
        bus.publish(EventDraft.of(module, nodeId, type, clock.tick())
                .withPeer(peer)
                .withMessage(message)
                .withData(Map.of("silentMillis", silent, "timeoutMillis", config.timeoutMillis())));
        for (FailureListener listener : listeners) {
            try {
                if (transition.suspected()) {
                    listener.onSuspected(peer, silent);
                } else {
                    listener.onAlive(peer, silent);
                }
            } catch (RuntimeException e) {
                log.warn("Node {}: failure listener threw on {} for node {}; continuing", nodeId, type, peer, e);
            }
        }
    }
}
