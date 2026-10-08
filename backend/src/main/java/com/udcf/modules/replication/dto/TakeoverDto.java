package com.udcf.modules.replication.dto;

import java.util.List;

/**
 * One change of the replication primary: what the new primary pulled before taking over, and
 * what it then pushed to every live backup. Both lists are empty on the first selection.
 *
 * <p>No dedicated test: a record; built and tested through ReplicationModuleTest.</p>
 *
 * @param previousPrimaryNodeId the node made primary before; null on the first selection
 * @param appliedFromCatchUp    items the new primary stored from its catch-ups
 * @param lamportTime           the new primary's Lamport time of the PRIMARY_SELECTED event
 */
public record TakeoverDto(
        Integer previousPrimaryNodeId,
        int newPrimaryNodeId,
        int appliedFromCatchUp,
        List<CatchUpDto> catchUps,
        List<AntiEntropyDto> pushes,
        long lamportTime
) {
}
