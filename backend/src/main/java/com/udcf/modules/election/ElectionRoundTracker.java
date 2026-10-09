package com.udcf.modules.election;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * At most one open election round, and the last finished one. Pure: injected clocks, no threads.
 *
 * <p>A round opens on an explicit trigger ({@link RoundTrigger}); while it is open, every other
 * attempt to open one returns empty, atomically, so when several nodes detect the same dead
 * leader at once exactly one round opens. It completes only after an {@code ELECTED} event was
 * seen in it and the caller reports agreement on a leader; the duration is measured from the
 * open to that moment. A round still open after the timeout is closed as TIMED_OUT, with no
 * duration and no leader.</p>
 *
 * <p>Thread safety: every method is synchronized and none waits.</p>
 */
public class ElectionRoundTracker {

    private static final double NANOS_PER_MILLI = 1_000_000d;

    private final LongSupplier nanoClock;
    private final Clock wallClock;
    private final long timeoutNanos;

    private long nextRoundId = 1;
    private ElectionRound current;
    private long currentStartedNanos;
    private boolean electedSeen;
    private ElectionRound last;

    public ElectionRoundTracker(LongSupplier nanoClock, Clock wallClock, long timeoutMillis) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock must not be null");
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be > 0, was " + timeoutMillis);
        }
        this.timeoutNanos = timeoutMillis * 1_000_000L;
    }

    /** Opens a round unless one is open. @return the new round, or empty if one was already open */
    public synchronized Optional<ElectionRound> open(ElectionAlgorithm algorithm, RoundTrigger trigger,
                                                     int initiatorNodeId) {
        Objects.requireNonNull(algorithm, "algorithm must not be null");
        Objects.requireNonNull(trigger, "trigger must not be null");
        if (current != null) {
            return Optional.empty();
        }
        current = new ElectionRound(nextRoundId++, algorithm, trigger, initiatorNodeId, wallClock.instant(),
                RoundOutcome.IN_PROGRESS, null, null);
        currentStartedNanos = nanoClock.getAsLong();
        electedSeen = false;
        return Optional.of(current);
    }

    /** A node elected a coordinator during the open round (no effect if none is open). */
    public synchronized void markElected() {
        if (current != null) {
            electedSeen = true;
        }
    }

    /**
     * Completes the open round with {@code leaderId} if an election was seen in it.
     *
     * @return the finished round (ELECTED, measured), or empty if nothing completed
     */
    public synchronized Optional<ElectionRound> complete(int leaderId) {
        if (current == null || !electedSeen) {
            return Optional.empty();
        }
        double millis = (nanoClock.getAsLong() - currentStartedNanos) / NANOS_PER_MILLI;
        return Optional.of(finish(RoundOutcome.ELECTED, leaderId, millis));
    }

    /** @return the round closed as TIMED_OUT, or empty if none was open past the timeout */
    public synchronized Optional<ElectionRound> expireIfOverdue() {
        if (current == null || nanoClock.getAsLong() - currentStartedNanos <= timeoutNanos) {
            return Optional.empty();
        }
        return Optional.of(finish(RoundOutcome.TIMED_OUT, null, null));
    }

    /** Drops an open round that could not start (its first node went down); it is not kept as last. */
    public synchronized void cancel(long roundId) {
        if (current != null && current.roundId() == roundId) {
            current = null;
            electedSeen = false;
        }
    }

    /** Forgets the open and the last round (a clean-slate reset). Round ids keep counting. */
    public synchronized void clear() {
        current = null;
        electedSeen = false;
        last = null;
    }

    public synchronized Optional<ElectionRound> current() {
        return Optional.ofNullable(current);
    }

    public synchronized Optional<ElectionRound> last() {
        return Optional.ofNullable(last);
    }

    private ElectionRound finish(RoundOutcome outcome, Integer leaderId, Double durationMillis) {
        ElectionRound done = new ElectionRound(current.roundId(), current.algorithm(), current.trigger(),
                current.initiatorNodeId(), current.startedAt(), outcome, leaderId, durationMillis);
        current = null;
        electedSeen = false;
        last = done;
        return done;
    }
}
