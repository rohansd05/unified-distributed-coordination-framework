package com.udcf.modules.clocksync.dto;

/**
 * Operational limits configured for Clock Synchronization traffic and log retention.
 */
public record ClockSyncLimitsDto(
        int defaultTrafficSeconds,
        int maxTrafficSeconds,
        int defaultMessagesPerSecond,
        int maxMessagesPerSecond,
        int retainedEventsCapacity
) {
}
