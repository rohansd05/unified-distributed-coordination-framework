package com.udcf.modules.clocksync;

import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;

/**
 * Pure protocol class for encoding and decoding clock UDP datagrams.
 *
 * <p>Wire formats:</p>
 * <ul>
 *   <li>{@code LAMPORT|<senderId>|<lamportTime>|<messageId>|<textPayload>}</li>
 *   <li>Legacy compatibility: {@code <senderId>|<lamportTime>|<textPayload>}</li>
 *   <li>{@code POLL|<daemonId>|<lamportTime>|<roundId>}</li>
 *   <li>{@code POLL_REPLY|<senderId>|<lamportTime>|<roundId>|<offsetMillis>}</li>
 *   <li>{@code ADJUST|<daemonId>|<lamportTime>|<roundId>|<adjustmentMillis>|<outlier>}</li>
 *   <li>{@code ADJUST_ACK|<senderId>|<lamportTime>|<roundId>|<afterOffsetMillis>}</li>
 * </ul>
 *
 * <p>Maximum datagram size is {@value #MAX_DATAGRAM_SIZE} bytes. Datagrams exceeding this
 * size or containing malformed syntax throw {@link ProtocolException}.</p>
 */
public final class ClockProtocol {

    /** Maximum accepted UDP datagram payload size in bytes. */
    public static final int MAX_DATAGRAM_SIZE = 1024;

    private static final String LAMPORT = "LAMPORT";
    private static final String POLL = "POLL";
    private static final String POLL_REPLY = "POLL_REPLY";
    private static final String ADJUST = "ADJUST";
    private static final String ADJUST_ACK = "ADJUST_ACK";

    private ClockProtocol() {
    }

    public static byte[] encode(ClockMessage message) {
        String line = switch (message.type()) {
            case LAMPORT -> LAMPORT + "|" + message.senderId() + "|" + message.lamportTime() + "|"
                    + message.id() + "|" + message.textPayload();
            case BERKELEY_POLL -> POLL + "|" + message.senderId() + "|" + message.lamportTime() + "|"
                    + message.id();
            case BERKELEY_POLL_REPLY -> POLL_REPLY + "|" + message.senderId() + "|" + message.lamportTime() + "|"
                    + message.id() + "|" + message.payloadValue();
            case BERKELEY_ADJUST -> ADJUST + "|" + message.senderId() + "|" + message.lamportTime() + "|"
                    + message.id() + "|" + message.payloadValue() + "|" + message.outlier();
            case BERKELEY_ADJUST_ACK -> ADJUST_ACK + "|" + message.senderId() + "|" + message.lamportTime() + "|"
                    + message.id() + "|" + message.payloadValue();
        };

        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_DATAGRAM_SIZE) {
            byte[] truncated = new byte[MAX_DATAGRAM_SIZE];
            System.arraycopy(bytes, 0, truncated, 0, MAX_DATAGRAM_SIZE);
            return truncated;
        }
        return bytes;
    }

    public static ClockMessage decode(byte[] data, int length) throws ProtocolException {
        if (data == null || length <= 0) {
            throw new ProtocolException("Empty or null datagram received");
        }
        if (length > MAX_DATAGRAM_SIZE) {
            throw new ProtocolException("Datagram size " + length + " exceeds maximum allowed " + MAX_DATAGRAM_SIZE);
        }

        String line = new String(data, 0, length, StandardCharsets.UTF_8).trim();
        if (line.isEmpty()) {
            throw new ProtocolException("Blank datagram received");
        }

        String[] parts = line.split("\\|", -1);

        // Check for legacy demo format: <senderId>|<lamportTime>|<payload>
        if (parts.length >= 3 && isNumeric(parts[0])) {
            try {
                int senderId = Integer.parseInt(parts[0]);
                long lamportTime = Long.parseLong(parts[1]);
                String payload = parts.length == 3 ? parts[2] : line.substring(line.indexOf('|', line.indexOf('|') + 1) + 1);
                return ClockMessage.lamport(senderId, lamportTime, 0L, payload);
            } catch (NumberFormatException e) {
                throw new ProtocolException("Malformed legacy datagram: " + line);
            }
        }

        if (parts.length < 2) {
            throw new ProtocolException("Insufficient fields in datagram: " + line);
        }

        String typeToken = parts[0];
        try {
            switch (typeToken) {
                case LAMPORT -> {
                    if (parts.length < 5) {
                        throw new ProtocolException("LAMPORT message requires 5 parts: " + line);
                    }
                    int senderId = Integer.parseInt(parts[1]);
                    long lamportTime = Long.parseLong(parts[2]);
                    long messageId = Long.parseLong(parts[3]);
                    // Combine any remaining parts into text payload (if payload contained '|')
                    String payload = parts[4];
                    if (parts.length > 5) {
                        int prefixLen = parts[0].length() + parts[1].length() + parts[2].length() + parts[3].length() + 4;
                        payload = line.substring(prefixLen);
                    }
                    return ClockMessage.lamport(senderId, lamportTime, messageId, payload);
                }
                case POLL -> {
                    if (parts.length < 4) {
                        throw new ProtocolException("POLL message requires 4 parts: " + line);
                    }
                    int daemonId = Integer.parseInt(parts[1]);
                    long lamportTime = Long.parseLong(parts[2]);
                    long roundId = Long.parseLong(parts[3]);
                    return ClockMessage.poll(daemonId, lamportTime, roundId);
                }
                case POLL_REPLY -> {
                    if (parts.length < 5) {
                        throw new ProtocolException("POLL_REPLY message requires 5 parts: " + line);
                    }
                    int senderId = Integer.parseInt(parts[1]);
                    long lamportTime = Long.parseLong(parts[2]);
                    long roundId = Long.parseLong(parts[3]);
                    long offsetMillis = Long.parseLong(parts[4]);
                    return ClockMessage.pollReply(senderId, lamportTime, roundId, offsetMillis);
                }
                case ADJUST -> {
                    if (parts.length < 6) {
                        throw new ProtocolException("ADJUST message requires 6 parts: " + line);
                    }
                    int daemonId = Integer.parseInt(parts[1]);
                    long lamportTime = Long.parseLong(parts[2]);
                    long roundId = Long.parseLong(parts[3]);
                    long adjustmentMillis = Long.parseLong(parts[4]);
                    boolean outlier = Boolean.parseBoolean(parts[5]);
                    return ClockMessage.adjust(daemonId, lamportTime, roundId, adjustmentMillis, outlier);
                }
                case ADJUST_ACK -> {
                    if (parts.length < 5) {
                        throw new ProtocolException("ADJUST_ACK message requires 5 parts: " + line);
                    }
                    int senderId = Integer.parseInt(parts[1]);
                    long lamportTime = Long.parseLong(parts[2]);
                    long roundId = Long.parseLong(parts[3]);
                    long afterOffsetMillis = Long.parseLong(parts[4]);
                    return ClockMessage.adjustAck(senderId, lamportTime, roundId, afterOffsetMillis);
                }
                default -> throw new ProtocolException("Unknown message type: '" + typeToken + "'");
            }
        } catch (NumberFormatException e) {
            throw new ProtocolException("Number format error in datagram: " + line);
        }
    }

    private static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isDigit(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
