package com.udcf.modules.clocksync.lamport;

import java.util.Comparator;
import java.util.Objects;

/**
 * Total causal ordering on {@link ClockEvent} instances, according to Lamport's 1978 paper:
 * "Time, Clocks, and the Ordering of Events in a Distributed System".
 *
 * <p>Lamport's partial order {@code a -> b} is extended to a deterministic total order by
 * defining {@code a => b} if and only if:</p>
 * <ol>
 *   <li>{@code a.lamportTime < b.lamportTime}, or</li>
 *   <li>{@code a.lamportTime == b.lamportTime} and {@code a.nodeId < b.nodeId}.</li>
 * </ol>
 *
 * <p>The tie-break on {@code nodeId} ensures that concurrent events with identical logical
 * timestamps have an unambiguous, deterministic global order identical across all nodes.</p>
 */
public final class LamportTotalOrder implements Comparator<ClockEvent> {

    public static final LamportTotalOrder INSTANCE = new LamportTotalOrder();

    public LamportTotalOrder() {
    }

    @Override
    public int compare(ClockEvent a, ClockEvent b) {
        Objects.requireNonNull(a, "event a must not be null");
        Objects.requireNonNull(b, "event b must not be null");

        int timeComparison = Long.compare(a.lamportTime(), b.lamportTime());
        if (timeComparison != 0) {
            return timeComparison;
        }
        return Integer.compare(a.nodeId(), b.nodeId());
    }
}
