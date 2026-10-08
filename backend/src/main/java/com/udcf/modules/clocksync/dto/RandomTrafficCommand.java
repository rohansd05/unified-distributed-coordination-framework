package com.udcf.modules.clocksync.dto;

/**
 * Command to start a background burst of random Lamport message traffic across live nodes.
 */
public record RandomTrafficCommand(
        Integer seconds,
        Integer messagesPerSecond
) {
}
