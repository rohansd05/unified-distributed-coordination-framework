package com.udcf.modules.replication.dto;

import com.udcf.modules.replication.ApplyResult;
import com.udcf.modules.replication.ConsistencyModel;

import java.util.List;

/**
 * One client write, as confirmed to the client, and later with its pushes.
 *
 * <p>No dedicated test: a record; built and tested through ReplicationModuleTest.</p>
 *
 * @param writeId              random 8-character id
 * @param primaryNodeId        the node that accepted the write
 * @param localResult          what the primary's own store did
 * @param confirmMillis        measured time until the client was told the write succeeded
 * @param simulated            true for ASYNCHRONOUS: its pushes wait a simulated delay (R7)
 * @param simulatedDelayMillis the simulated delay before each push; 0 for SYNCHRONOUS
 * @param simulatedReason      why the delay is simulated; null when not simulated
 * @param replicationState     PENDING until every push has ended
 * @param backupNodeIds        the backups pushed to, in order
 * @param pushes               one per backup once COMPLETE; empty while PENDING
 * @param takeover             the primary change this write caused; null if none
 */
public record WriteDto(
        String writeId,
        ConsistencyModel model,
        int primaryNodeId,
        ItemDto item,
        ApplyResult localResult,
        double confirmMillis,
        boolean simulated,
        long simulatedDelayMillis,
        String simulatedReason,
        ReplicationState replicationState,
        List<Integer> backupNodeIds,
        List<PushDto> pushes,
        TakeoverDto takeover
) {
}
