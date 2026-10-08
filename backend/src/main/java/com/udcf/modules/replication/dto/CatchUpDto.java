package com.udcf.modules.replication.dto;

/**
 * One catch-up: a new primary pulled one live peer's whole store and merged it.
 *
 * <p>No dedicated test: a record; built and tested through ReplicationModuleTest.</p>
 *
 * @param pulled         items pulled; null if the pull failed
 * @param applied        items stored from the pull; null if the pull failed
 * @param alreadyCurrent items already held exactly; null if the pull failed
 * @param stale          items held in a newer version; null if the pull failed
 * @param staleEpoch     items refused by the epoch fence; null if the pull failed
 * @param sourceEpoch    the source's store epoch, used as the sender epoch; null if the pull failed
 * @param latencyMillis  measured time of the pull and merge
 * @param failure        why nothing was merged; null on success
 */
public record CatchUpDto(
        int sourceNodeId,
        boolean completed,
        Integer pulled,
        Integer applied,
        Integer alreadyCurrent,
        Integer stale,
        Integer staleEpoch,
        Long sourceEpoch,
        double latencyMillis,
        String failure
) {
}
