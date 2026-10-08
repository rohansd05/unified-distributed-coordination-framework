package com.udcf.modules.replication.dto;

/**
 * One anti-entropy run: the source's whole store pushed to one target.
 *
 * <p>No dedicated test: a record; built and tested through ReplicationModuleTest.</p>
 *
 * @param pushed             items the target acknowledged
 * @param applied            items the target was missing or held older, now stored
 * @param alreadyCurrent     items the target already held exactly
 * @param stale              items the target held a newer version of
 * @param staleEpoch         items refused because the source was superseded
 * @param latencyMillis      measured time of the whole run
 * @param failure            why the run stopped early; null if it completed
 */
public record AntiEntropyDto(
        int sourceNodeId,
        int targetNodeId,
        boolean completed,
        int pushed,
        int applied,
        int alreadyCurrent,
        int stale,
        int staleEpoch,
        int chunksPlanned,
        int chunksAcknowledged,
        double latencyMillis,
        String failure
) {
}
