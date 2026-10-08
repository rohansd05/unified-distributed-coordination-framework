package com.udcf.modules.clocksync.dto;

import java.time.Instant;

/**
 * A single causal event in the timeline, with messageId for space-time diagram linkage.
 */
public record TimelineEventDto(
        int nodeId,
        String type,
        long lamportTime,
        Integer peerId,
        long messageId,
        Long causedByTime,
        String description,
        Instant wallTime
) {
}
