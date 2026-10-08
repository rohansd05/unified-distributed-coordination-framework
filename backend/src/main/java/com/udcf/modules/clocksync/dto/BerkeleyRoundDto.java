package com.udcf.modules.clocksync.dto;

import java.util.List;

/**
 * Result representation of a Berkeley clock synchronization round.
 */
public record BerkeleyRoundDto(
        long roundId,
        int daemonNodeId,
        long outlierThresholdMillis,
        long averageOffsetMillis,
        long spreadBeforeMillis,
        long spreadAfterMillis,
        List<Integer> participatingNodes,
        List<Integer> outlierNodes,
        List<NodeAdjustmentDto> adjustments,
        boolean simulated,
        String simulatedReason
) {
}
