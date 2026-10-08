package com.udcf.modules.replication.dto;

/**
 * One replica's version of one key in the side-by-side view.
 *
 * <p>No dedicated test: a record; built and tested through ReplicationModuleTest.</p>
 *
 * @param item  the replica's version; null if it does not hold the key or could not be read
 * @param state the comparison with the reference; null when there is no readable reference
 */
public record CellDto(int nodeId, ItemDto item, ReplicaState state) {
}
