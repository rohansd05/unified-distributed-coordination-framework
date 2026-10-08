package com.udcf.modules.replication.dto;

import java.util.List;

/**
 * Every replica's store side by side, read over TCP, each cell compared with the reference.
 *
 * <p>No dedicated test: a record; built and tested through ReplicationModuleTest.</p>
 *
 * @param referenceNodeId the node last made primary; null if none yet
 * @param consistent      true if every reachable replica matches the reference; null when there is
 *                        no readable reference. Unreachable replicas are left out, see {@code replicas}.
 * @param divergences     the number of cells that are not CURRENT or ABSENT among reachable replicas;
 *                        null when there is no readable reference
 * @param replicas        one column per node, in node order
 * @param rows            one row per key held by any reachable replica, in key order
 */
public record ReplicasDto(
        Integer referenceNodeId,
        Boolean consistent,
        Integer divergences,
        List<ReplicaColumnDto> replicas,
        List<KeyRowDto> rows
) {
}
