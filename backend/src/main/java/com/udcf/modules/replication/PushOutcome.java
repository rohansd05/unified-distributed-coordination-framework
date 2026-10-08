package com.udcf.modules.replication;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * How one push of one item to one backup ended.
 *
 * <p>Only an {@link PushStatus#ACKED} push carries a result, the backup's epoch and a latency;
 * every other status carries a detail instead. Nothing is filled with a placeholder, so a
 * missing figure is never mistaken for a measured 0 (R7).</p>
 *
 * <p>Covered by ReplicationRecordsTest.</p>
 *
 * @param backupId      the backup pushed to, at least 1
 * @param status        how the push ended
 * @param result        what the backup's store did; ACKED only
 * @param backupEpoch   the backup's store epoch in its reply; ACKED only
 * @param latencyMillis the measured TCP round trip, excluding any simulated delay; ACKED only
 * @param detail        why there is no reply; every status except ACKED
 */
public record PushOutcome(int backupId, PushStatus status, Optional<ApplyResult> result, OptionalLong backupEpoch,
                          OptionalDouble latencyMillis, Optional<String> detail) {

    public PushOutcome {
        if (backupId < 1) {
            throw new IllegalArgumentException("backupId must be >= 1, was " + backupId);
        }
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(backupEpoch, "backupEpoch must not be null");
        Objects.requireNonNull(latencyMillis, "latencyMillis must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
        boolean acked = status == PushStatus.ACKED;
        if (acked != result.isPresent() || acked != backupEpoch.isPresent() || acked != latencyMillis.isPresent()
                || acked == detail.isPresent()) {
            throw new IllegalArgumentException("an ACKED push has a result, epoch and latency and no detail;"
                    + " any other push has only a detail (status " + status + ")");
        }
        if (acked && (!Double.isFinite(latencyMillis.getAsDouble()) || latencyMillis.getAsDouble() < 0)) {
            throw new IllegalArgumentException("latencyMillis must be finite and >= 0");
        }
    }

    public static PushOutcome acked(int backupId, ApplyResult result, long backupEpoch, double latencyMillis) {
        return new PushOutcome(backupId, PushStatus.ACKED, Optional.of(result), OptionalLong.of(backupEpoch),
                OptionalDouble.of(latencyMillis), Optional.empty());
    }

    public static PushOutcome withoutReply(int backupId, PushStatus status, String detail) {
        return new PushOutcome(backupId, status, Optional.empty(), OptionalLong.empty(), OptionalDouble.empty(),
                Optional.of(Objects.requireNonNull(detail, "detail must not be null")));
    }

    /** True if the backup replied, whatever it did with the item. */
    public boolean acknowledged() {
        return status == PushStatus.ACKED;
    }
}
