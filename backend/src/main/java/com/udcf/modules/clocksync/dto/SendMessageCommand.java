package com.udcf.modules.clocksync.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Command to send a point-to-point Lamport message over UDP.
 */
public record SendMessageCommand(
        @NotNull Integer from,
        @NotNull Integer to,
        String payload
) {
}
