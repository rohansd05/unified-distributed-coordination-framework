package com.udcf.modules.replication;

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The replicated key-value store held by every node, with its epoch fence.
 *
 * <p>This is where the consistency rule lives. {@link #apply(DataItem, long)} is the single
 * entry point for every update, whether it came from a local client write on the primary,
 * a replication message on a backup or an anti-entropy push, and it refuses anything that
 * is not newer than what is already stored. That refusal is what makes the store tolerate
 * out-of-order delivery: an older update arriving late is discarded instead of overwriting
 * the newer value.</p>
 *
 * <h2>Epoch rules (shared with Experiment 8)</h2>
 * <ul>
 *   <li>The store has an epoch, starting at {@link #INITIAL_EPOCH}. It only ever rises on
 *       the write path: through {@link #observeEpoch(long)} or through an apply whose sender
 *       epoch is higher. Only {@link #clear()} lowers it.</li>
 *   <li>Every apply names the <b>sender's</b> epoch, the term of the primary that sent it.
 *       The item's own epoch may not exceed it ({@link IllegalArgumentException}).</li>
 *   <li>Sender epoch below the store's epoch: {@link ApplyResult#STALE_EPOCH}. The sender
 *       is a superseded primary; nothing is stored and nothing is raised.</li>
 *   <li>Sender epoch above the store's epoch: the store's epoch is raised to it first,
 *       then the item is applied. A new primary's first push therefore teaches a backup
 *       the new epoch.</li>
 *   <li>An item whose own epoch is lower than the store's, from a sender at or above the
 *       store's epoch, is <b>not</b> refused: it competes by last-writer-wins (epoch, then
 *       Lamport time, then origin). Old data is repaired, never lost.</li>
 * </ul>
 *
 * <h2>Atomicity guarantee</h2>
 * <p>Once {@link #observeEpoch(long) observeEpoch(n)} has returned, or an apply that raised
 * the epoch to n has returned, no update from a sender with an epoch below n is ever
 * stored. A per-key {@code compute} plus an atomic counter is not enough for this: a write
 * to key A could pass the fence check, another thread could raise the epoch through key B
 * and return, and A's superseded write would then land after the raise. So the fence uses
 * a {@link ReentrantReadWriteLock}: applies share the read lock from the fence check until
 * their {@code compute} has finished, and raising the epoch takes the write lock, which
 * waits for every apply already past the check. An apply that must raise the epoch does so
 * through the write lock <b>before</b> it takes the read lock, so no thread ever upgrades a
 * read lock to a write lock (which would deadlock).</p>
 *
 * <p>Ported from legacy-demos/exp05-replication. Differences: the epoch fence (legacy
 * Experiment 8 kept it in the node, here it is in the store so both experiments share
 * it); {@link ApplyResult} instead of a boolean, so an identical redelivery is a
 * {@link ApplyResult#DUPLICATE} and no longer counted as a stale rejection; a duplicate
 * counter; {@link #clear()}; no wire encoding ({@code encodeAll} moves to the transport,
 * E5b).</p>
 */
public class DataStore {

    /** Epoch of a fresh store, and of a store after {@link #clear()}. */
    public static final long INITIAL_EPOCH = 1L;

    /**
     * Test seam: lets a test pause the write path at fixed points to force an
     * interleaving. Production code uses {@link #NO_PROBE}.
     */
    interface Probe {
        /** Called first thing in an apply, before any lock is taken. */
        void beforeApply(DataItem item);

        /** Called inside the read lock, after the fence check passed and before {@code compute}. */
        void afterFenceCheck(DataItem item);
    }

    static final Probe NO_PROBE = new Probe() {
        @Override
        public void beforeApply(DataItem item) {
        }

        @Override
        public void afterFenceCheck(DataItem item) {
        }
    };

    private final ConcurrentHashMap<String, DataItem> items = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock fenceLock = new ReentrantReadWriteLock();
    private final Probe probe;

    /** Written only while holding the write lock; volatile so the pre-check outside the lock sees it. */
    private volatile long epoch = INITIAL_EPOCH;

    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong stale = new AtomicLong();
    private final AtomicLong staleEpoch = new AtomicLong();

    public DataStore() {
        this(NO_PROBE);
    }

    DataStore(Probe probe) {
        this.probe = Objects.requireNonNull(probe, "probe must not be null");
    }

    /**
     * Applies an update the sender stamped with its own epoch, which is the item's:
     * {@code apply(item, item.epoch())}.
     */
    public ApplyResult apply(DataItem item) {
        Objects.requireNonNull(item, "item must not be null");
        return apply(item, item.epoch());
    }

    /**
     * The single guard for every write: applies {@code item} if, and only if, its sender is
     * not superseded and the item is newer than the stored version (see the class comment).
     *
     * @param item        the update
     * @param senderEpoch the epoch of the primary sending it, at least the item's epoch
     * @return what happened; never null
     * @throws IllegalArgumentException if {@code item.epoch() > senderEpoch}
     */
    public ApplyResult apply(DataItem item, long senderEpoch) {
        Objects.requireNonNull(item, "item must not be null");
        if (item.epoch() > senderEpoch) {
            throw new IllegalArgumentException("item epoch " + item.epoch()
                    + " is above the sender's epoch " + senderEpoch);
        }
        probe.beforeApply(item);
        if (senderEpoch > epoch) {
            raiseTo(senderEpoch);   // write lock, taken and released before the read lock
        }
        ReentrantReadWriteLock.ReadLock readLock = fenceLock.readLock();
        readLock.lock();
        try {
            if (senderEpoch < epoch) {
                count(ApplyResult.STALE_EPOCH);
                return ApplyResult.STALE_EPOCH;
            }
            probe.afterFenceCheck(item);
            ApplyResult[] outcome = new ApplyResult[1];
            items.compute(item.key(), (key, existing) -> {
                if (item.isNewerThan(existing)) {
                    outcome[0] = ApplyResult.APPLIED;
                    return item;
                }
                // Equal records are a redelivery. The same version with a different value
                // is a protocol fault: refused as stale, never overwritten.
                outcome[0] = item.equals(existing) ? ApplyResult.DUPLICATE : ApplyResult.STALE;
                return existing;
            });
            count(outcome[0]);
            return outcome[0];
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Raises the store's epoch to {@code observed} if that is higher; never lowers it.
     * Used by a node that learns of a newer primary other than through a write, for
     * example a recovering old primary that queries its peers (Experiment 8).
     *
     * <p>Returns only after every apply already past the fence check has finished (see the
     * class comment).</p>
     *
     * @param observed an epoch seen elsewhere, at least 1
     * @return the store's epoch after the call
     */
    public long observeEpoch(long observed) {
        if (observed < INITIAL_EPOCH) {
            throw new IllegalArgumentException("observed epoch must be >= " + INITIAL_EPOCH + ", was " + observed);
        }
        return raiseTo(observed);
    }

    private long raiseTo(long candidate) {
        ReentrantReadWriteLock.WriteLock writeLock = fenceLock.writeLock();
        writeLock.lock();
        try {
            if (candidate > epoch) {
                epoch = candidate;
            }
            return epoch;
        } finally {
            writeLock.unlock();
        }
    }

    /** The store's current epoch. */
    public long epoch() {
        return epoch;
    }

    public Optional<DataItem> get(String key) {
        return Optional.ofNullable(items.get(key));
    }

    /** A detached, unmodifiable copy sorted by key, so two replicas can be compared line by line. */
    public SortedMap<String, DataItem> snapshot() {
        return Collections.unmodifiableSortedMap(new TreeMap<>(items));
    }

    public int size() {
        return items.size();
    }

    public long appliedCount() {
        return applied.get();
    }

    public long duplicateCount() {
        return duplicates.get();
    }

    public long staleCount() {
        return stale.get();
    }

    public long staleEpochCount() {
        return staleEpoch.get();
    }

    /**
     * Whole-store reset: removes every item, zeroes the counters and puts the epoch back to
     * {@link #INITIAL_EPOCH}.
     *
     * <p>Only for tests and for the module's reset. A cluster reset restarts every Lamport
     * clock at 0, so a store left on a raised epoch would refuse every new write as coming
     * from a superseded sender. This is the only way the epoch is ever lowered; nothing on
     * the write path does it.</p>
     */
    public void clear() {
        ReentrantReadWriteLock.WriteLock writeLock = fenceLock.writeLock();
        writeLock.lock();
        try {
            items.clear();
            applied.set(0);
            duplicates.set(0);
            stale.set(0);
            staleEpoch.set(0);
            epoch = INITIAL_EPOCH;
        } finally {
            writeLock.unlock();
        }
    }

    private void count(ApplyResult outcome) {
        switch (outcome) {
            case APPLIED -> applied.incrementAndGet();
            case DUPLICATE -> duplicates.incrementAndGet();
            case STALE -> stale.incrementAndGet();
            case STALE_EPOCH -> staleEpoch.incrementAndGet();
        }
    }
}
