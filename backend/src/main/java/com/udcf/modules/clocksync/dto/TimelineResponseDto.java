package com.udcf.modules.clocksync.dto;

import java.util.List;

/**
 * Retained causal timeline events for visualization (e.g. space-time diagram in E3d).
 */
public record TimelineResponseDto(
        int limit,
        int returnedCount,
        int retainedEventsCount,
        int droppedEventsCount,
        List<TimelineEventDto> events
) {
}
