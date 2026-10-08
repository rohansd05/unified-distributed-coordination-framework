package com.udcf.modules.replication.dto;

import com.udcf.core.cluster.NodeStatus;

/**
 * One node as the replication module sees it, read in-process and without starting anything.
 *
 * <p>No dedicated test: a record; built and tested through ReplicationModuleTest.</p>
 *
 * @param role           PRIMARY or BACKUP by the selector (lowest live node); null if every node
 *                       is crashed
 * @param actingPrimary  true if its replication service acts as primary now
 * @param serviceRunning true if its replication service is listening
 * @param epoch          its store epoch (kept across a crash, as the store is); null if its
 *                       service never started
 * @param itemCount      items in its store; null unless its service is running (a crashed node
 *                       does not serve)
 */
public record NodeReplicaDto(
        int nodeId,
        NodeStatus nodeStatus,
        String role,
        boolean actingPrimary,
        boolean serviceRunning,
        int port,
        Long epoch,
        Integer itemCount
) {
}
