package com.udcf.modules.replication.dto;

import java.time.Instant;

/**
 * One backup's replication health as measured by the current primary: real acknowledgements and
 * failures only; anti-entropy is not counted. Counts are real counts (0 means none happened);
 * figures that need an acknowledgement are null until the first one.
 *
 * <p>No dedicated test: a record; built and tested through ReplicationModuleTest.</p>
 *
 * @param staleRejections       acknowledgements that refused the item as STALE
 * @param staleEpochRejections  acknowledgements that refused the sender's epoch
 * @param failures              pushes that got no reply (crashed, silent or ERROR)
 * @param averageLatencyMillis  null before the first acknowledgement
 * @param maxLatencyMillis      null before the first acknowledgement
 * @param lastSync              wall time of the latest acknowledgement; null before the first
 */
public record HealthRowDto(
        int backupNodeId,
        long acks,
        long applied,
        long duplicates,
        long staleRejections,
        long staleEpochRejections,
        long failures,
        Double averageLatencyMillis,
        Double maxLatencyMillis,
        Instant lastSync
) {
}
