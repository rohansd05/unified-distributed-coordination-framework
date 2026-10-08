package com.udcf.modules.clocksync.dto;

import java.time.Instant;

/**
 * Result of sending a point-to-point Lamport message over UDP.
 *
 * <p><b>UDP Honesty:</b> If sent to a crashed node, {@code deliveryStatus} is {@code "UNKNOWN"}
 * because UDP is a connectionless, unacknowledged transport. The datagram was transmitted,
 * and the sender's clock ticked, but delivery cannot be guaranteed without higher-level ACK.</p>
 */
public record SendLamportResult(
        int from,
        int to,
        long messageId,
        long lamportTime,
        String payload,
        String deliveryStatus,
        String deliveryNote,
        Instant wallTime
) {
}
