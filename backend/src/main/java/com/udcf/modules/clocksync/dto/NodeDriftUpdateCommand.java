package com.udcf.modules.clocksync.dto;

/**
 * Command to update the simulated drift parameters of a node's physical clock.
 */
public record NodeDriftUpdateCommand(
        Long initialOffsetMillis,
        Double driftRateMsPerSec
) {
}
