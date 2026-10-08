package com.udcf.modules.clocksync.dto;

import java.util.List;

/**
 * Result of checking causal invariants across the retained event log window.
 */
public record CausalVerificationDto(
        int totalEventsChecked,
        int receiveEventsChecked,
        int violationsCount,
        boolean passed,
        String summary,
        List<CausalViolationDto> violations,
        int retainedEventsCount,
        int droppedEventsCount,
        String retainedWindowNote
) {
}
