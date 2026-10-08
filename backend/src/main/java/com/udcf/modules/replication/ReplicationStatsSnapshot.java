package com.udcf.modules.replication;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * A consistent copy of one backup's replication health, taken under the stats lock.
 *
 * <p>No dedicated test: a record; its contents are covered by {@code ReplicationStatsTest} (R6).</p>
 *
 * @param backupNodeId          the backup these figures describe
 * @param acks                  replies received: applied + duplicates + staleRejections + staleEpochRejections
 * @param applied               acks reporting {@link ApplyResult#APPLIED}
 * @param duplicates            acks reporting {@link ApplyResult#DUPLICATE}
 * @param staleRejections       acks reporting {@link ApplyResult#STALE} only
 * @param staleEpochRejections  acks reporting {@link ApplyResult#STALE_EPOCH}
 * @param failures              attempts that got no reply (refused connection, timeout)
 * @param averageLatencyMillis  mean round trip over every ack; empty before the first
 * @param maxLatencyMillis      longest round trip; empty before the first ack
 * @param lastSync              wall time of the latest ack, display only; empty before the first
 */
public record ReplicationStatsSnapshot(
        int backupNodeId,
        long acks,
        long applied,
        long duplicates,
        long staleRejections,
        long staleEpochRejections,
        long failures,
        OptionalDouble averageLatencyMillis,
        OptionalDouble maxLatencyMillis,
        Optional<Instant> lastSync
) {

    public ReplicationStatsSnapshot {
        Objects.requireNonNull(averageLatencyMillis, "averageLatencyMillis must not be null");
        Objects.requireNonNull(maxLatencyMillis, "maxLatencyMillis must not be null");
        Objects.requireNonNull(lastSync, "lastSync must not be null");
    }
}
