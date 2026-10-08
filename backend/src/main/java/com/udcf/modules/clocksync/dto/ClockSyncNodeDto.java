package com.udcf.modules.clocksync.dto;

/**
 * Node state snapshot for Clock Synchronization overview.
 */
public record ClockSyncNodeDto(
        int nodeId,
        String status,
        long lamportValue,
        NodeDriftResponseDto simulatedDrift,
        boolean serviceRunning
) {
}
