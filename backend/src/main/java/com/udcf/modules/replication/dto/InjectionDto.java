package com.udcf.modules.replication.dto;

/**
 * One out-of-order delivery: an older version of a key sent to a backup after the newer one.
 *
 * <p>No dedicated test: a record; built and tested through ReplicationModuleTest.</p>
 *
 * @param currentItem the primary's version when the stale one was built
 * @param staleItem   the version delivered: same key, epoch and origin, one Lamport tick older
 * @param push        how the delivery ended
 * @param rejected    true if the backup refused it as STALE (the expected outcome)
 */
public record InjectionDto(
        int primaryNodeId,
        int backupNodeId,
        String key,
        ItemDto currentItem,
        ItemDto staleItem,
        PushDto push,
        boolean rejected
) {
}
