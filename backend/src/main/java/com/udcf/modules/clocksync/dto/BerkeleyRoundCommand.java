package com.udcf.modules.clocksync.dto;

/**
 * Command to trigger a Berkeley clock synchronization round coordinated by the time daemon.
 */
public record BerkeleyRoundCommand(
        Long outlierThresholdMillis
) {
}
