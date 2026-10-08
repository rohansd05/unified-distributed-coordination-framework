package com.udcf.modules.clocksync.dto;

import java.time.Instant;

/**
 * Accepted response for an asynchronously coordinated Berkeley synchronization round.
 */
public record BerkeleyRoundAcceptedDto(
        long roundId,
        int daemonNodeId,
        long outlierThresholdMillis,
        String status,
        Instant startedAt
) {
}
