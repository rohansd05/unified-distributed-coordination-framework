package com.udcf.modules.clocksync;

import java.util.Objects;

/**
 * Immutable representation of a datagram exchanged over the UDP clock port.
 *
 * <p>Carries type, sender node id, sender's Lamport timestamp (link L4), message/round id,
 * and protocol payload fields.</p>
 *
 * @param type         the message type
 * @param senderId     id of the sending cluster node (&gt;= 1)
 * @param lamportTime  sender's Lamport clock value at transmission (&gt;= 0)
 * @param id           message id (for Lamport messages) or round id (for Berkeley messages)
 * @param payloadValue offset or adjustment value in milliseconds
 * @param outlier      whether the node was classified as an outlier during Berkeley averaging
 * @param rttMillis    round-trip time in milliseconds (Cristian compensation)
 * @param textPayload  human-readable text or transaction payload
 */
public record ClockMessage(
        ClockMessageType type,
        int senderId,
        long lamportTime,
        long id,
        long payloadValue,
        boolean outlier,
        double rttMillis,
        String textPayload
) {

    public ClockMessage {
        Objects.requireNonNull(type, "type must not be null");
        if (senderId < 1) {
            throw new IllegalArgumentException("senderId must be >= 1, was " + senderId);
        }
        if (lamportTime < 0) {
            throw new IllegalArgumentException("lamportTime must be >= 0, was " + lamportTime);
        }
        textPayload = textPayload == null ? "" : textPayload;
    }

    public static ClockMessage lamport(int senderId, long lamportTime, long messageId, String textPayload) {
        return new ClockMessage(ClockMessageType.LAMPORT, senderId, lamportTime, messageId, 0L, false, 0.0, textPayload);
    }

    public static ClockMessage poll(int daemonId, long lamportTime, long roundId) {
        return new ClockMessage(ClockMessageType.BERKELEY_POLL, daemonId, lamportTime, roundId, 0L, false, 0.0, "");
    }

    public static ClockMessage pollReply(int senderId, long lamportTime, long roundId, long offsetMillis) {
        return new ClockMessage(ClockMessageType.BERKELEY_POLL_REPLY, senderId, lamportTime, roundId, offsetMillis, false, 0.0, "");
    }

    public static ClockMessage adjust(int daemonId, long lamportTime, long roundId, long adjustmentMillis, boolean outlier) {
        return new ClockMessage(ClockMessageType.BERKELEY_ADJUST, daemonId, lamportTime, roundId, adjustmentMillis, outlier, 0.0, "");
    }

    public static ClockMessage adjustAck(int senderId, long lamportTime, long roundId, long afterOffsetMillis) {
        return new ClockMessage(ClockMessageType.BERKELEY_ADJUST_ACK, senderId, lamportTime, roundId, afterOffsetMillis, false, 0.0, "");
    }
}
