package com.udcf.modules.replication.dto;

import com.udcf.modules.replication.ApplyResult;
import com.udcf.modules.replication.PushStatus;

/**
 * How one push to one backup ended.
 *
 * <p>No dedicated test: a record; built and tested through ReplicationModuleTest.</p>
 *
 * @param result        what the backup's store did; null unless ACKED
 * @param backupEpoch   the backup's store epoch in its reply; null unless ACKED
 * @param latencyMillis the measured TCP round trip, without any simulated delay; null unless ACKED
 * @param detail        why there was no reply; null when ACKED
 */
public record PushDto(
        int backupNodeId,
        PushStatus status,
        ApplyResult result,
        Long backupEpoch,
        Double latencyMillis,
        String detail
) {
}
