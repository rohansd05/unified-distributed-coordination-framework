package com.udcf.modules.clocksync.lamport;

/**
 * The three distinct types of events in Lamport's logical clock model:
 * <ul>
 *   <li>{@link #LOCAL} - Rule 1: internal execution event, advances clock by 1</li>
 *   <li>{@link #SEND} - Rule 2: message send, advances clock by 1 and attaches timestamp</li>
 *   <li>{@link #RECV} - Rule 3: message receive, updates clock to max(local, received) + 1</li>
 * </ul>
 */
public enum ClockEventType {
    LOCAL,
    SEND,
    RECV
}
