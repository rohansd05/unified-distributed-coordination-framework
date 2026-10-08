package com.udcf.modules.replication.dto;

import com.udcf.core.cluster.NodeStatus;

/**
 * One replica in the side-by-side view, as read over TCP.
 *
 * <p>No dedicated test: a record; built and tested through ReplicationModuleTest.</p>
 *
 * @param reference true for the node last made primary, the one every cell is compared with
 * @param reachable false if its store could not be read
 * @param epoch     its store epoch; null if not reachable
 * @param itemCount items it holds; null if not reachable
 * @param error     why it could not be read; null when reachable
 */
public record ReplicaColumnDto(
        int nodeId,
        NodeStatus nodeStatus,
        boolean reference,
        boolean reachable,
        Long epoch,
        Integer itemCount,
        String error
) {
}
