package com.udcf.sync;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lamport logical clock — the production class used by every UDCF node.
 *
 * <p>Implements Lamport's three rules:</p>
 * <ul>
 *   <li><b>Rule 1</b> (internal event): {@code clock = clock + 1}</li>
 *   <li><b>Rule 2</b> (send): {@code clock = clock + 1}, and the value travels with the message</li>
 *   <li><b>Rule 3</b> (receive): {@code clock = max(local, received) + 1}</li>
 * </ul>
 *
 * <p>The counter is an {@link AtomicLong} rather than a plain {@code long} because a UDCF
 * node is multithreaded: several worker threads may advance the clock at the same instant.
 * The receive rule uses {@code updateAndGet} so the read-compare-write happens as one
 * indivisible operation — splitting it would allow two threads to interleave, lose an
 * update, and let the clock move backwards.</p>
 */
public class LamportClock {

    private final AtomicLong counter = new AtomicLong(0);

    /** Rule 1 and Rule 2 — a local event occurred, or a message is about to be sent. */
    public long tick() {
        return counter.incrementAndGet();
    }

    /**
     * Rule 3 — a message arrived carrying {@code receivedTime}.
     * The max forces this clock strictly past the sender's, preserving causality.
     */
    public long update(long receivedTime) {
        return counter.updateAndGet(local -> Math.max(local, receivedTime) + 1);
    }

    /** Current value without advancing the clock. */
    public long current() {
        return counter.get();
    }

    /** Resets to zero. Used only to start a clean demonstration. */
    public void reset() {
        counter.set(0);
    }
}
