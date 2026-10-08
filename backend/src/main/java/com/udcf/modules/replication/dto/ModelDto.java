package com.udcf.modules.replication.dto;

import com.udcf.modules.replication.ConsistencyModel;

/**
 * A consistency model, explained in plain words, with its simulated part labelled (R7).
 *
 * <p>No dedicated test: a record; its texts are checked in ReplicationModuleTest.</p>
 *
 * @param simulated       true if part of its behaviour is simulated
 * @param simulatedReason why; null when not simulated
 */
public record ModelDto(
        ConsistencyModel model,
        String description,
        String guarantee,
        boolean simulated,
        String simulatedReason
) {
}
