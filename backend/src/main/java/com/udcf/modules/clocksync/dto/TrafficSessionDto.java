package com.udcf.modules.clocksync.dto;

import java.time.Instant;

/**
 * Accepted session acknowledgment for background random traffic generation.
 */
public record TrafficSessionDto(
        String sessionId,
        int seconds,
        int messagesPerSecond,
        String status,
        Instant startedAt
) {
}
