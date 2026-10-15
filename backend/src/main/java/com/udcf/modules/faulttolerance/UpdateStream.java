package com.udcf.modules.faulttolerance;

import com.udcf.modules.replication.ConsistencyModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Experiment 8's continuous client update stream: {@link SystemUpdate#numbered} updates sent one
 * at a time through a {@link Submitter} ({@link FailoverCluster#submit} in production), the next
 * one only after the previous one finished, no faster than the chosen rate.
 *
 * <p><b>Budget.</b> All streams of one reset session may send at most {@code maxUpdates}
 * updates; a stream that uses the last one stops with {@link StopReason#LIMIT_REACHED}.
 * {@link #reset()} gives a fresh budget. Sequence numbers are never reused, not even after a
 * reset: an update still being retried at a reset may be accepted later, and its key must not
 * collide with a new update's.</p>
 *
 * <p><b>Threads.</b> Updates are sent from {@value #THREAD_NAME}; their outcomes arrive on the
 * submitter's thread. The listener is called outside this object's lock and must not block.
 * Delays are scheduled, never slept.</p>
 *
 * <p>Covered by UpdateStreamTest (scripted submitter).</p>
 */
final class UpdateStream implements AutoCloseable {

    static final String THREAD_NAME = "udcf-faulttolerance-stream";

    private static final long CLOSE_TIMEOUT_MILLIS = 5_000;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final Logger log = LoggerFactory.getLogger(UpdateStream.class);

    /** Sends one update; the future completes when it was accepted or given up. */
    @FunctionalInterface
    interface Submitter {
        CompletableFuture<UpdateOutcome> submit(SystemUpdate update, ConsistencyModel model);
    }

    /** Told about every outcome of this reset session and every stop except a reset. Must not block. */
    interface Listener {
        void onOutcome(UpdateOutcome outcome);

        void onStopped(Snapshot snapshot);
    }

    /** Why a stream stopped. */
    enum StopReason {
        REQUESTED,
        LIMIT_REACHED,
        ERROR
    }

    /** What {@link #start} did. */
    enum StartResult {
        STARTED,
        ALREADY_RUNNING,
        LIMIT_REACHED
    }

    /**
     * The stream at one moment.
     *
     * @param running          whether a stream runs now
     * @param mode             the model of the next update
     * @param ratePerSecond    the rate of the current or last stream; null if none since the reset
     * @param sent             updates the current or last stream sent
     * @param acknowledged     of those, accepted (the client was told they succeeded)
     * @param gaveUp           of those, given up after every attempt
     * @param inFlightSequence the update being sent now; null if none
     * @param maxUpdates       the budget of one reset session
     * @param remainingUpdates what is left of it
     * @param startedAt        when the current or last stream started; null if none
     * @param stoppedAt        when the last stream stopped; null while running or if none
     * @param stopReason       why it stopped; null while running or if none
     */
    record Snapshot(boolean running, ConsistencyModel mode, Integer ratePerSecond, int sent, int acknowledged,
                    int gaveUp, Integer inFlightSequence, int maxUpdates, int remainingUpdates, Instant startedAt,
                    Instant stoppedAt, StopReason stopReason) {
    }

    private final Submitter submitter;
    private final Listener listener;
    private final int maxUpdates;
    private final Clock clock;
    private final LongSupplier nanoClock;
    private final ScheduledThreadPoolExecutor executor;

    // guarded by this
    private long session;
    private long streamId;
    private boolean running;
    private ConsistencyModel mode = ConsistencyModel.SYNCHRONOUS;
    private Integer ratePerSecond;
    private long intervalNanos;
    private int sent;
    private int acknowledged;
    private int gaveUp;
    private int sessionSent;
    private int nextSequence = 1;
    private Integer inFlightSequence;
    private long inFlightSession;
    private long inFlightStream;
    private long lastSendNanos;
    private Instant startedAt;
    private Instant stoppedAt;
    private StopReason stopReason;

    UpdateStream(Submitter submitter, Listener listener, int maxUpdates, Clock clock, LongSupplier nanoClock) {
        this.submitter = Objects.requireNonNull(submitter, "submitter must not be null");
        this.listener = Objects.requireNonNull(listener, "listener must not be null");
        if (maxUpdates < 1) {
            throw new IllegalArgumentException("maxUpdates must be >= 1, was " + maxUpdates);
        }
        this.maxUpdates = maxUpdates;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        this.executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        this.executor.setRemoveOnCancelPolicy(true);
    }

    /** Starts a stream at {@code ratePerSecond} with {@code model}, unless one runs or the budget is used. */
    StartResult start(ConsistencyModel model, int ratePerSecond) {
        Objects.requireNonNull(model, "model must not be null");
        if (ratePerSecond < 1) {
            throw new IllegalArgumentException("ratePerSecond must be >= 1, was " + ratePerSecond);
        }
        synchronized (this) {
            if (running) {
                return StartResult.ALREADY_RUNNING;
            }
            if (sessionSent >= maxUpdates) {
                return StartResult.LIMIT_REACHED;
            }
            running = true;
            streamId++;
            mode = model;
            this.ratePerSecond = ratePerSecond;
            intervalNanos = NANOS_PER_SECOND / ratePerSecond;
            sent = 0;
            acknowledged = 0;
            gaveUp = 0;
            startedAt = clock.instant();
            stoppedAt = null;
            stopReason = null;
            if (inFlightSequence == null) {
                schedule(0);   // otherwise the update still in flight schedules the first one
            }
        }
        return StartResult.STARTED;
    }

    /** Stops the running stream; an update in flight still finishes and is counted. False if none runs. */
    boolean stop() {
        Snapshot stopped;
        synchronized (this) {
            if (!running) {
                return false;
            }
            stopLocked(StopReason.REQUESTED);
            stopped = snapshotLocked();
        }
        listener.onStopped(stopped);
        return true;
    }

    /** The model of the next update, running or not. Returns the previous one. */
    synchronized ConsistencyModel setMode(ConsistencyModel model) {
        ConsistencyModel previous = mode;
        mode = Objects.requireNonNull(model, "model must not be null");
        return previous;
    }

    /** A fresh session: stops without notice, forgets every count and restores the budget; the sequence goes on. */
    synchronized void reset() {
        session++;
        streamId++;
        running = false;
        mode = ConsistencyModel.SYNCHRONOUS;
        ratePerSecond = null;
        intervalNanos = 0;
        sent = 0;
        acknowledged = 0;
        gaveUp = 0;
        sessionSent = 0;
        inFlightSequence = null;
        startedAt = null;
        stoppedAt = null;
        stopReason = null;
    }

    synchronized Snapshot snapshot() {
        return snapshotLocked();
    }

    /** Stops the stream thread, waiting at most 5 s. An update in flight is the submitter's to finish. */
    @Override
    public void close() {
        synchronized (this) {
            running = false;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(THREAD_NAME + " did not finish within " + CLOSE_TIMEOUT_MILLIS + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ internals

    /** Stream thread: sends the next update of stream {@code id}, if it still runs. */
    private void tick(long tickSession, long id) {
        SystemUpdate update;
        ConsistencyModel model;
        Snapshot limitReached = null;
        synchronized (this) {
            if (!running || tickSession != session || id != streamId || inFlightSequence != null) {
                return;
            }
            if (sessionSent >= maxUpdates) {
                stopLocked(StopReason.LIMIT_REACHED);
                limitReached = snapshotLocked();
                update = null;
                model = null;
            } else {
                int sequence = nextSequence++;
                sessionSent++;
                sent++;
                inFlightSequence = sequence;
                inFlightSession = session;
                inFlightStream = streamId;
                lastSendNanos = nanoClock.getAsLong();
                update = SystemUpdate.numbered(sequence);
                model = mode;
            }
        }
        if (limitReached != null) {
            listener.onStopped(limitReached);
            return;
        }
        CompletableFuture<UpdateOutcome> future;
        try {
            future = Objects.requireNonNull(submitter.submit(update, model), "submitter returned null");
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        long sentSession = tickSession;
        long sentStream = id;
        future.whenComplete((outcome, error) -> finished(sentSession, sentStream, update.sequence(), outcome, error));
    }

    /** Submitter's thread: an update ended. Counts it, schedules the next one and tells the listener. */
    private void finished(long sentSession, long sentStream, int sequence, UpdateOutcome outcome, Throwable error) {
        Snapshot errorStop = null;
        synchronized (this) {
            if (sentSession != session) {
                return;   // sent before a reset: neither counted nor reported
            }
            if (inFlightSequence != null && inFlightSequence == sequence && inFlightSession == sentSession
                    && inFlightStream == sentStream) {
                inFlightSequence = null;
            }
            if (sentStream == streamId) {
                if (error != null) {
                    log.warn("Experiment 8: update {} failed", sequence, error);
                    if (running) {
                        stopLocked(StopReason.ERROR);
                        errorStop = snapshotLocked();
                    }
                } else if (outcome.accepted()) {
                    acknowledged++;
                } else {
                    gaveUp++;
                }
            }
            if (running && inFlightSequence == null) {
                long due = lastSendNanos + intervalNanos - nanoClock.getAsLong();
                schedule(Math.max(0, due));
            }
        }
        if (outcome != null) {
            listener.onOutcome(outcome);
        }
        if (errorStop != null) {
            listener.onStopped(errorStop);
        }
    }

    private void schedule(long delayNanos) {   // caller holds this
        long scheduledSession = session;
        long scheduledStream = streamId;
        try {
            executor.schedule(() -> tick(scheduledSession, scheduledStream), delayNanos, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            running = false;   // closed
        }
    }

    private void stopLocked(StopReason reason) {   // caller holds this
        running = false;
        stoppedAt = clock.instant();
        stopReason = reason;
    }

    private Snapshot snapshotLocked() {   // caller holds this
        return new Snapshot(running, mode, ratePerSecond, sent, acknowledged, gaveUp, inFlightSequence, maxUpdates,
                maxUpdates - sessionSent, startedAt, stoppedAt, stopReason);
    }
}
