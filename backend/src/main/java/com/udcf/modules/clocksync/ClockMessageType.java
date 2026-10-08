package com.udcf.modules.clocksync;

/**
 * Message types exchanged over UDP on a node's clock port ({@code ports().clock()}).
 */
public enum ClockMessageType {
    /** Application Lamport message carrying a stamped logical clock. */
    LAMPORT,
    /** Berkeley time-daemon polling a node for its physical clock offset. */
    BERKELEY_POLL,
    /** Reply from a node to the time-daemon with its current physical offset. */
    BERKELEY_POLL_REPLY,
    /** Adjustment instruction sent by the time-daemon to a node. */
    BERKELEY_ADJUST,
    /** Acknowledgment from a node after applying the Berkeley adjustment. */
    BERKELEY_ADJUST_ACK
}
