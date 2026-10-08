package com.udcf.modules.clocksync.dto;

/**
 * Command to record a local Lamport event on a node.
 */
public record LocalEventCommand(
        String description
) {
}
