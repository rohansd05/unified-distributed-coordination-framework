package com.udcf.modules.election;

/**
 * How an election round ended.
 *
 * <ul>
 *   <li>{@code IN_PROGRESS}: not ended yet.</li>
 *   <li>{@code ELECTED}: every live node agreed on one live leader; the duration is measured.</li>
 *   <li>{@code TIMED_OUT}: no agreement within the round timeout; nothing is measured (R7).</li>
 * </ul>
 *
 * <p>No dedicated test: a plain enum with no behaviour.</p>
 */
public enum RoundOutcome {
    IN_PROGRESS,
    ELECTED,
    TIMED_OUT
}
