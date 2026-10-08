package com.udcf.modules.clocksync.dto;

import java.time.Instant;

/**
 * Result of recording a local Lamport event on a node.
 */
public record LocalEventResult(
        int nodeId,
        long lamportTime,
        String description,
        Instant wallTime
) {
}
