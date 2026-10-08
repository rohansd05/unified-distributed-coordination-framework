package com.udcf.modules.replication.dto;

/**
 * One key read from one replica over TCP, compared with the reference replica.
 *
 * <p>No dedicated test: a record; built and tested through ReplicationModuleTest.</p>
 *
 * @param referenceNodeId the node last made primary, read for comparison; null if none yet
 * @param reachable       false if the replica could not be read
 * @param item            the replica's version; null if it does not hold the key or could not be read
 * @param referenceItem   the reference's version; null if it does not hold the key or could not be read
 * @param state           the comparison; UNREACHABLE when not reachable; null when the reference
 *                        is unknown or could not be read
 * @param error           why the replica could not be read; null when reachable
 */
public record ReadDto(
        int nodeId,
        String key,
        Integer referenceNodeId,
        boolean reachable,
        ItemDto item,
        ItemDto referenceItem,
        ReplicaState state,
        String error
) {
}
