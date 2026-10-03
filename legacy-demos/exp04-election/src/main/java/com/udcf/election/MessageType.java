package com.udcf.election;

/**
 * Wire protocol for inter-node election traffic.
 *
 * <p>Every message is a single UDP datagram of the form:</p>
 * <pre>
 *   type | senderId | lamportTime | payload
 * </pre>
 *
 * <p>The {@code lamportTime} field is what integrates this experiment with
 * Experiment 3: every election message carries the sender's logical clock, so the
 * election log can be replayed in correct causal order even though UDP delivers
 * messages out of order.</p>
 */
public enum MessageType {

    /** Bully: a lower node asks higher nodes whether any of them is alive. */
    ELECTION,

    /** Bully: a higher node answers, suppressing the lower node's candidacy. */
    OK,

    /** Bully and Ring: the winner announces itself to everybody. */
    COORDINATOR,

    /** Ring: the election token, carrying the list of node IDs visited so far. */
    RING_ELECTION,

    /** Ring: the result token, circulated so every node learns the winner. */
    RING_COORDINATOR,

    /** Heartbeat request sent to the current coordinator. */
    PING,

    /** Heartbeat reply. Absence of this is what triggers failure detection. */
    PONG,

    /** Ring: liveness check before forwarding, so a dead successor can be skipped. */
    PROBE,

    /** Ring: reply to PROBE. */
    PROBE_ACK
}
