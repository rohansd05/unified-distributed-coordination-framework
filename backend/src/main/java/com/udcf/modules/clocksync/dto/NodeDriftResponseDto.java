package com.udcf.modules.clocksync.dto;

/**
 * Snapshot of a node's simulated physical clock drift.
 *
 * <p><b>R7 Honesty:</b> Tagged with {@code simulated: true} and explicit explanation
 * because physical clock drift is simulated mathematically; all network operations are real UDP.</p>
 */
public record NodeDriftResponseDto(
        int nodeId,
        long offsetMillis,
        double driftRateMsPerSec,
        long simulatedTime,
        boolean simulated,
        String simulatedReason
) {
}
