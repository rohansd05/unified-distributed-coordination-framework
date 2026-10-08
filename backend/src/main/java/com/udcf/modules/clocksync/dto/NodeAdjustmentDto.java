package com.udcf.modules.clocksync.dto;

/**
 * Adjustment details for a single node participating in a Berkeley synchronization round.
 */
public record NodeAdjustmentDto(
        int nodeId,
        long beforeOffsetMillis,
        long adjustmentMillis,
        long afterOffsetMillis,
        boolean outlier,
        double rttMillis
) {
}
