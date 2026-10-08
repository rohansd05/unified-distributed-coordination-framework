package com.udcf.modules.clocksync.lamport;

import java.time.Instant;
import java.util.Objects;

/**
 * An immutable record representing one logical clock event across cluster nodes.
 *
 * <p>Carries causal lineage necessary for total causal ordering and causal invariant
 * verification.</p>
 *
 * @param nodeId       the node on which the event occurred (&gt;= 1)
 * @param type         LOCAL, SEND or RECV
 * @param lamportTime  the Lamport timestamp resulting from the event (&gt;= 0)
 * @param peerId       the peer node involved, or 0 for a local event
 * @param messageId    the unique message id linking send and receive events; 0 for local events
 * @param causedByTime for a RECV, the timestamp carried by the incoming message;
 *                     -1 otherwise. Allows proving that receive is ordered strictly
 *                     after the send that caused it.
 * @param description  human-readable description (never null)
 * @param wallTime     reference timestamp for human inspection (never null, never used for causal ordering)
 */
public record ClockEvent(
        int nodeId,
        ClockEventType type,
        long lamportTime,
        int peerId,
        long messageId,
        long causedByTime,
        String description,
        Instant wallTime
) implements Comparable<ClockEvent> {

    public ClockEvent {
        if (nodeId < 1) {
            throw new IllegalArgumentException("nodeId must be >= 1, was " + nodeId);
        }
        Objects.requireNonNull(type, "type must not be null");
        if (lamportTime < 0) {
            throw new IllegalArgumentException("lamportTime must be >= 0, was " + lamportTime);
        }
        if (peerId < 0) {
            throw new IllegalArgumentException("peerId must be >= 0, was " + peerId);
        }
        Objects.requireNonNull(wallTime, "wallTime must not be null");
        Objects.requireNonNull(description, "description must not be null");
    }

    public ClockEvent(
            int nodeId,
            ClockEventType type,
            long lamportTime,
            int peerId,
            long causedByTime,
            String description,
            Instant wallTime
    ) {
        this(nodeId, type, lamportTime, peerId, 0L, causedByTime, description, wallTime);
    }

    public static ClockEvent local(int nodeId, long lamportTime, String description, Instant wallTime) {
        return new ClockEvent(nodeId, ClockEventType.LOCAL, lamportTime, 0, 0L, -1L, description, wallTime);
    }

    public static ClockEvent send(int nodeId, long lamportTime, int peerId, String description, Instant wallTime) {
        return new ClockEvent(nodeId, ClockEventType.SEND, lamportTime, peerId, 0L, -1L, description, wallTime);
    }

    public static ClockEvent send(int nodeId, long lamportTime, int peerId, long messageId, String description, Instant wallTime) {
        return new ClockEvent(nodeId, ClockEventType.SEND, lamportTime, peerId, messageId, -1L, description, wallTime);
    }

    public static ClockEvent receive(int nodeId, long lamportTime, int peerId, long causedByTime, String description, Instant wallTime) {
        return new ClockEvent(nodeId, ClockEventType.RECV, lamportTime, peerId, 0L, causedByTime, description, wallTime);
    }

    public static ClockEvent receive(int nodeId, long lamportTime, int peerId, long messageId, long causedByTime, String description, Instant wallTime) {
        return new ClockEvent(nodeId, ClockEventType.RECV, lamportTime, peerId, messageId, causedByTime, description, wallTime);
    }

    public boolean isLocal() {
        return type == ClockEventType.LOCAL;
    }

    public boolean isSend() {
        return type == ClockEventType.SEND;
    }

    public boolean isReceive() {
        return type == ClockEventType.RECV;
    }

    @Override
    public int compareTo(ClockEvent other) {
        return LamportTotalOrder.INSTANCE.compare(this, other);
    }
}
