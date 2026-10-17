package com.udcf.modules.clocksync.dto;

import java.time.Instant;

/**
 * The most recent Berkeley round, when it was aborted instead of finishing (its daemon crashed
 * during the round). {@code latestRound} keeps the last round that really finished.
 *
 * @param roundId      the id returned by {@code POST /berkeley-rounds}
 * @param daemonNodeId the daemon that coordinated it
 * @param reason       a plain sentence saying why it was aborted
 */
public record AbortedRoundDto(
        long roundId,
        int daemonNodeId,
        String reason,
        Instant abortedAt
) {
}
