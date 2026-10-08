package com.udcf.modules.replication.dto;

/**
 * One stored version: the value and the version that decides last-writer-wins.
 *
 * <p>No dedicated test: a record; built and tested through ReplicationModuleTest.</p>
 *
 * @param lamportTime the writer's Lamport time when the version was created
 * @param originNode  the node that created it
 * @param epoch       the term of the primary that accepted it
 */
public record ItemDto(String key, String value, long lamportTime, int originNode, long epoch) {
}
