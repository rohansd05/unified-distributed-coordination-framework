package com.udcf.modules.election;

public record ElectionMessage(
        ElectionMessageType type,
        int senderId,
        long lamportTime,
        String payload
) {
    public static final int MAX_DATAGRAM_SIZE = 1024;

    public String toWire() {
        return type.name() + "|" + senderId + "|" + lamportTime + "|" + (payload == null ? "" : payload);
    }

    public static ElectionMessage fromWire(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("Empty or null datagram received");
        }
        if (raw.length() > MAX_DATAGRAM_SIZE) {
            throw new IllegalArgumentException("Datagram size " + raw.length() + " exceeds maximum allowed " + MAX_DATAGRAM_SIZE);
        }
        String[] parts = raw.split("\\|", 4);
        if (parts.length < 3) {
            throw new IllegalArgumentException("Insufficient fields in datagram: " + raw);
        }
        try {
            ElectionMessageType type = ElectionMessageType.valueOf(parts[0]);
            int senderId = Integer.parseInt(parts[1]);
            long lamportTime = Long.parseLong(parts[2]);
            String payload = parts.length > 3 ? parts[3] : "";
            return new ElectionMessage(type, senderId, lamportTime, payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed datagram: " + raw, e);
        }
    }
}
