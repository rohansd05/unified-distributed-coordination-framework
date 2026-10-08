package com.udcf.modules.replication;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-backup replication health, measured rather than estimated (R7).
 *
 * <p>Every figure comes from an actual acknowledgement round trip or an actual failure,
 * reported by the caller. Every ack is a measured round trip, so its latency and time are
 * recorded whatever the outcome; outcomes are counted separately, and the stale count
 * covers {@link ApplyResult#STALE} only.</p>
 *
 * <p>Ported from legacy-demos/exp05-replication. Differences: a running count, sum and
 * maximum instead of an unbounded list of every latency; latency recorded on every ack
 * (legacy only on a stored item); per-outcome counts, with duplicates and stale-epoch
 * refusals no longer counted as stale; average and maximum empty, not 0, before the first
 * ack; the time of an ack is passed in by the caller rather than read from the system
 * clock, and "ms ago" formatting moves to the UI; invalid latencies rejected, so no NaN or
 * infinity can appear; a {@link ReentrantLock} instead of {@code synchronized}.</p>
 */
public class ReplicationStats {

    /**
     * Longest latency accepted: one hour. Anything above is a measurement bug; the cap also
     * keeps the running sum finite.
     */
    public static final double MAX_LATENCY_MILLIS = 3_600_000d;

    private final int backupNodeId;
    private final ReentrantLock lock = new ReentrantLock();

    // Guarded by lock.
    private long applied;
    private long duplicates;
    private long staleRejections;
    private long staleEpochRejections;
    private long failures;
    private double totalLatencyMillis;
    private double maxLatencyMillis;
    private Instant lastSync;

    /** @param backupNodeId the backup these figures describe, at least 1 */
    public ReplicationStats(int backupNodeId) {
        if (backupNodeId < 1) {
            throw new IllegalArgumentException("backupNodeId must be >= 1, was " + backupNodeId);
        }
        this.backupNodeId = backupNodeId;
    }

    public int backupNodeId() {
        return backupNodeId;
    }

    /**
     * Records one reply from the backup.
     *
     * @param outcome       what the backup did with the update
     * @param latencyMillis the measured round trip, finite, 0 to {@link #MAX_LATENCY_MILLIS}
     * @param at            when the reply arrived (wall time, display only)
     */
    public void recordAck(ApplyResult outcome, double latencyMillis, Instant at) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(at, "at must not be null");
        if (!Double.isFinite(latencyMillis) || latencyMillis < 0 || latencyMillis > MAX_LATENCY_MILLIS) {
            throw new IllegalArgumentException("latencyMillis must be finite and 0-" + MAX_LATENCY_MILLIS
                    + ", was " + latencyMillis);
        }
        lock.lock();
        try {
            switch (outcome) {
                case APPLIED -> applied++;
                case DUPLICATE -> duplicates++;
                case STALE -> staleRejections++;
                case STALE_EPOCH -> staleEpochRejections++;
            }
            totalLatencyMillis += latencyMillis;
            maxLatencyMillis = Math.max(maxLatencyMillis, latencyMillis);
            if (lastSync == null || at.isAfter(lastSync)) {
                lastSync = at;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Records an attempt that got no reply at all: the backup could not be reached. */
    public void recordFailure() {
        lock.lock();
        try {
            failures++;
        } finally {
            lock.unlock();
        }
    }

    /** All figures, read together under the lock. */
    public ReplicationStatsSnapshot snapshot() {
        lock.lock();
        try {
            long acks = applied + duplicates + staleRejections + staleEpochRejections;
            return new ReplicationStatsSnapshot(
                    backupNodeId, acks, applied, duplicates, staleRejections, staleEpochRejections, failures,
                    acks == 0 ? OptionalDouble.empty() : OptionalDouble.of(totalLatencyMillis / acks),
                    acks == 0 ? OptionalDouble.empty() : OptionalDouble.of(maxLatencyMillis),
                    Optional.ofNullable(lastSync));
        } finally {
            lock.unlock();
        }
    }

    /** Back to the empty state. */
    public void reset() {
        lock.lock();
        try {
            applied = 0;
            duplicates = 0;
            staleRejections = 0;
            staleEpochRejections = 0;
            failures = 0;
            totalLatencyMillis = 0d;
            maxLatencyMillis = 0d;
            lastSync = null;
        } finally {
            lock.unlock();
        }
    }
}
