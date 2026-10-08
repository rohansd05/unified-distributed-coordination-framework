package com.udcf.modules.replication;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Outcome of one catch-up: a node pulled another replica's whole store over TCP (DUMP pages)
 * and merged it into its own with {@link AntiEntropy#merge}, using the source's store epoch as
 * the sender epoch. The reverse direction of anti-entropy, used by a node about to take over as
 * primary ({@link ReplicaSet#primary()}).
 *
 * <p>Covered by ReplicationRecordsTest (validation) and ReplicationNodeServiceTest.</p>
 *
 * @param sourceNodeId  the replica pulled from, at least 1
 * @param merged        what the merge did; empty if the pull failed (nothing was merged)
 * @param sourceEpoch   the source's store epoch, used as the sender epoch; empty if the pull failed
 * @param latencyMillis measured time of the pull and merge, including a failed pull
 * @param failure       why nothing was merged; empty on success
 */
public record CatchUpReport(int sourceNodeId, Optional<AntiEntropyResult> merged, OptionalLong sourceEpoch,
                            double latencyMillis, Optional<String> failure) {

    public CatchUpReport {
        if (sourceNodeId < 1) {
            throw new IllegalArgumentException("sourceNodeId must be >= 1, was " + sourceNodeId);
        }
        Objects.requireNonNull(merged, "merged must not be null");
        Objects.requireNonNull(sourceEpoch, "sourceEpoch must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        if (merged.isPresent() == failure.isPresent() || merged.isPresent() != sourceEpoch.isPresent()) {
            throw new IllegalArgumentException("a catch-up has a merge result and source epoch, or a failure, never both");
        }
        if (!Double.isFinite(latencyMillis) || latencyMillis < 0) {
            throw new IllegalArgumentException("latencyMillis must be finite and >= 0, was " + latencyMillis);
        }
    }

    public static CatchUpReport merged(int sourceNodeId, AntiEntropyResult merged, long sourceEpoch, double latencyMillis) {
        return new CatchUpReport(sourceNodeId, Optional.of(merged), OptionalLong.of(sourceEpoch), latencyMillis,
                Optional.empty());
    }

    public static CatchUpReport failed(int sourceNodeId, double latencyMillis, String failure) {
        return new CatchUpReport(sourceNodeId, Optional.empty(), OptionalLong.empty(), latencyMillis,
                Optional.of(failure));
    }

    /** True if the source's store was pulled and merged. */
    public boolean completed() {
        return failure.isEmpty();
    }
}
