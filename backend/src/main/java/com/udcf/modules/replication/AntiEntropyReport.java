package com.udcf.modules.replication;

import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of one anti-entropy run from one sender to one target, over TCP.
 *
 * <p>The sender's whole store is pushed in chunks of {@code batch-size} items, one SYNC
 * message each (at least one, so an empty store still proves the target is reachable). The
 * counts cover only the chunks the target acknowledged; if a chunk fails, the run stops there
 * and {@code failure} says why. Anti-entropy is not counted in {@link ReplicationStats}.</p>
 *
 * <p>Covered by ReplicationRecordsTest (validation) and ReplicationNodeServiceTest.</p>
 *
 * @param targetNodeId       the replica brought up to date, at least 1
 * @param merged             the summed merge counts of the acknowledged chunks
 * @param chunksPlanned      SYNC messages the run needed, at least 1
 * @param chunksAcknowledged SYNC messages the target answered, 0 to {@code chunksPlanned}
 * @param latencyMillis      measured time of the whole run, including a failed chunk
 * @param failure            why the run stopped early; empty if every chunk was acknowledged
 */
public record AntiEntropyReport(int targetNodeId, AntiEntropyResult merged, int chunksPlanned,
                                int chunksAcknowledged, double latencyMillis, Optional<String> failure) {

    public AntiEntropyReport {
        if (targetNodeId < 1) {
            throw new IllegalArgumentException("targetNodeId must be >= 1, was " + targetNodeId);
        }
        Objects.requireNonNull(merged, "merged must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        if (chunksPlanned < 1 || chunksAcknowledged < 0 || chunksAcknowledged > chunksPlanned) {
            throw new IllegalArgumentException("need 1 <= chunksPlanned and 0 <= chunksAcknowledged <= chunksPlanned, was "
                    + chunksAcknowledged + "/" + chunksPlanned);
        }
        if (failure.isPresent() == (chunksAcknowledged == chunksPlanned)) {
            throw new IllegalArgumentException("a failure is reported if, and only if, a chunk was not acknowledged");
        }
        if (!Double.isFinite(latencyMillis) || latencyMillis < 0) {
            throw new IllegalArgumentException("latencyMillis must be finite and >= 0, was " + latencyMillis);
        }
    }

    /** True if every chunk was acknowledged. */
    public boolean completed() {
        return failure.isEmpty();
    }
}
