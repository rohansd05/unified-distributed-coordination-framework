package com.udcf.modules.mpi;

import com.udcf.core.clock.LamportClock;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.modules.rmi.CallContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The work behind a node's {@link CollectiveEndpoint}: no registry, export or socket (E9b exports
 * this object). Its methods throw no {@code RemoteException}.
 *
 * <p><b>Order of every call</b> (link L4, as in Experiment 1): the arguments are checked first,
 * so an invalid call changes nothing; then {@code clock.update(senderLamport)}; then the work;
 * then the reply is stamped with {@code clock.tick()}.</p>
 *
 * <p><b>Store.</b> A received payload is kept by collective id, a copy, at most
 * {@code receive-store-size} of them. A payload for an id already held replaces it and becomes
 * the newest; when the store is full, the oldest entry is dropped. Thread-safe: RMI delivers
 * calls on several threads, so every access to the store holds one lock; the clock is atomic.</p>
 */
public final class CollectiveEndpointLogic implements CollectiveEndpoint {

    /** One stored payload. */
    private record Stored(int rank, double[] values) {
    }

    private final int nodeId;
    private final LamportClock clock;
    private final MpiProperties properties;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<Long, Stored> store = new LinkedHashMap<>();   // insertion order: oldest first

    public CollectiveEndpointLogic(int nodeId, LamportClock clock, MpiProperties properties) {
        if (nodeId < 1) {
            throw new IllegalArgumentException("nodeId must be >= 1, was " + nodeId);
        }
        this.nodeId = nodeId;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /** The logic of {@code node}, on the node's shared Lamport clock (never a second clock). */
    public static CollectiveEndpointLogic forNode(ClusterNode node, MpiProperties properties) {
        Objects.requireNonNull(node, "node must not be null");
        return new CollectiveEndpointLogic(node.id(), node.clock(), properties);
    }

    public int nodeId() {
        return nodeId;
    }

    @Override
    public ReceiveReceipt receive(CallContext context, long collectiveId, int rank, double[] values) {
        Objects.requireNonNull(context, "context must not be null");
        requireIds(collectiveId, rank);
        Objects.requireNonNull(values, "values must not be null");
        requireLength(values.length);

        clock.update(context.senderLamport());
        double[] copy = values.clone();
        String checksum = VectorChecksum.of(copy);
        lock.lock();
        try {
            store.remove(collectiveId);   // a repeated id becomes the newest entry
            store.put(collectiveId, new Stored(rank, copy));
            Iterator<Long> oldest = store.keySet().iterator();
            while (store.size() > properties.receiveStoreSize()) {
                oldest.next();
                oldest.remove();
            }
        } finally {
            lock.unlock();
        }
        return new ReceiveReceipt(nodeId, clock.tick(), collectiveId, rank, copy.length, checksum);
    }

    @Override
    public Contribution contribute(CallContext context, long collectiveId, int rank, int count, long seed) {
        Objects.requireNonNull(context, "context must not be null");
        requireIds(collectiveId, rank);
        requireLength(count);

        clock.update(context.senderLamport());
        double[] values = PayloadGenerator.contribution(nodeId, count, seed);
        return new Contribution(nodeId, clock.tick(), collectiveId, rank, values, VectorChecksum.of(values));
    }

    /** A copy of the payload held for {@code collectiveId}, if it is still stored. */
    public Optional<double[]> received(long collectiveId) {
        lock.lock();
        try {
            Stored stored = store.get(collectiveId);
            return stored == null ? Optional.empty() : Optional.of(stored.values().clone());
        } finally {
            lock.unlock();
        }
    }

    /** The collective ids held, oldest first (the order in which they would be dropped). */
    public List<Long> storedIds() {
        lock.lock();
        try {
            return List.copyOf(new ArrayList<>(store.keySet()));
        } finally {
            lock.unlock();
        }
    }

    /** The rank each held payload was sent to, by collective id. */
    public Map<Long, Integer> storedRanks() {
        lock.lock();
        try {
            Map<Long, Integer> ranks = new LinkedHashMap<>();
            store.forEach((id, stored) -> ranks.put(id, stored.rank()));
            return ranks;
        } finally {
            lock.unlock();
        }
    }

    private static void requireIds(long collectiveId, int rank) {
        if (collectiveId < 1) {
            throw new IllegalArgumentException("collectiveId must be >= 1, was " + collectiveId);
        }
        if (rank < 0) {
            throw new IllegalArgumentException("rank must be >= 0, was " + rank);
        }
    }

    private void requireLength(int length) {
        if (length < 1 || length > properties.maxPayloadElements()) {
            throw new IllegalArgumentException("a payload must have 1 to " + properties.maxPayloadElements()
                    + " elements, was " + length);
        }
    }
}
