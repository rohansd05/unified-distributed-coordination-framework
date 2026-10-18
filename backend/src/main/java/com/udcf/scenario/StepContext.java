package com.udcf.scenario;

import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * What a step's body uses to act and observe: event anchors, waits with the step's deadline,
 * facts and the run's shared memory. One per step, used only on the scenario runner thread.
 *
 * <p><b>Anchors, not step starts.</b> Events caused by one action can be published while an
 * earlier step is still waiting. A step that performs an action therefore takes an
 * {@link #anchor(String) anchor} (the bus sequence just before the action), and every later wait
 * for a consequence of that action accepts events newer than that anchor, whichever step waits.</p>
 *
 * <p><b>Observation.</b> {@link #awaitEvent} subscribes to the bus for the wait and also polls the
 * bus history every poll interval, so an event published before the wait began, or a notification
 * the bus dropped, is still found. The earliest matching event by bus sequence wins. Every wait
 * fails the step once the step's deadline has passed, and as soon as someone else resets the
 * cluster.</p>
 */
public final class StepContext {

    static final String CLUSTER_MODULE = "cluster";
    static final String CLUSTER_RESET = "CLUSTER_RESET";
    private static final String ANCHOR_PREFIX = "anchor.";

    private final ClusterEventBus bus;
    private final RunMemory memory;
    private final long ownResetSequence;
    private final long deadlineNanos;
    private final long startNanos;
    private final long pollMillis;
    private final LongSupplier nanoClock;
    private final Map<String, Object> facts = Collections.synchronizedMap(new LinkedHashMap<>());

    StepContext(ClusterEventBus bus, RunMemory memory, long ownResetSequence, long deadlineMillis, long pollMillis,
                LongSupplier nanoClock) {
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.memory = Objects.requireNonNull(memory, "memory must not be null");
        this.ownResetSequence = ownResetSequence;
        this.pollMillis = pollMillis;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        this.startNanos = nanoClock.getAsLong();
        this.deadlineNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(deadlineMillis);
    }

    public RunMemory memory() {
        return memory;
    }

    /** Records an observed fact of this step (shown in the run as {@code facts}). Insertion order is kept. */
    public void fact(String key, Object value) {
        facts.put(Objects.requireNonNull(key, "key must not be null"), value);
    }

    Map<String, Object> facts() {
        synchronized (facts) {
            return new LinkedHashMap<>(facts);
        }
    }

    /** The bus sequence now: every event published from here on has a higher one. */
    public long mark() {
        return bus.publishedCount();
    }

    /** Takes {@link #mark()} just before an action and stores it under {@code name} for the run's later steps. */
    public long anchor(String name) {
        long sequence = mark();
        memory.remember(ANCHOR_PREFIX + name, sequence);
        return sequence;
    }

    /** An anchor an earlier step (or this one) took. */
    public long anchorOf(String name) {
        return memory.recall(ANCHOR_PREFIX + name, Long.class);
    }

    /** The step's message for an unmet expectation; throw it. */
    public StepFailedException fail(String message) {
        return new StepFailedException(message);
    }

    /**
     * Waits for the earliest event of {@code module} newer than {@code after} that matches.
     *
     * @param what a phrase for the failure message, for example "node 4 to become primary"
     * @throws StepFailedException if the step's deadline passes first, or the cluster is reset by someone else
     */
    public ClusterEvent awaitEvent(long after, String module, Predicate<ClusterEvent> match, String what)
            throws InterruptedException {
        Predicate<ClusterEvent> wanted = event -> event.sequence() > after && event.module().equals(module)
                && match.test(event);
        AtomicReference<ClusterEvent> delivered = new AtomicReference<>();
        AtomicReference<ClusterEvent> foreignReset = new AtomicReference<>();
        Object signal = new Object();
        try (ClusterEventBus.Subscription ignored = bus.subscribe(event -> {
            boolean wake = false;
            if (wanted.test(event)) {
                delivered.accumulateAndGet(event, StepContext::earlier);
                wake = true;
            } else if (isForeignReset(event)) {
                foreignReset.compareAndSet(null, event);
                wake = true;
            }
            if (wake) {
                synchronized (signal) {
                    signal.notifyAll();
                }
            }
        })) {
            while (true) {
                checkNoForeignReset(foreignReset.get());
                ClusterEvent found = earlier(delivered.get(), bus.query(module, null, Integer.MAX_VALUE).stream()
                        .filter(wanted).min(Comparator.comparingLong(ClusterEvent::sequence)).orElse(null));
                if (found != null) {
                    return found;
                }
                long remaining = remainingMillis();
                if (remaining <= 0) {
                    throw timedOut(what);
                }
                synchronized (signal) {
                    if (delivered.get() == null && foreignReset.get() == null) {
                        signal.wait(Math.max(1, Math.min(pollMillis, remaining)));
                    }
                }
            }
        }
    }

    /** {@link #awaitEvent(long, String, Predicate, String)} for one event type. */
    public ClusterEvent awaitEvent(long after, String module, String type, Predicate<ClusterEvent> match,
                                   String what) throws InterruptedException {
        return awaitEvent(after, module, event -> event.type().equals(type) && match.test(event), what);
    }

    /**
     * Polls {@code state} every poll interval until {@code done} holds, and returns that value.
     *
     * @throws StepFailedException if the step's deadline passes first, or the cluster is reset by someone else
     */
    public <T> T awaitState(Supplier<T> state, Predicate<T> done, String what) throws InterruptedException {
        while (true) {
            checkNoForeignReset(null);
            T value = state.get();
            if (done.test(value)) {
                return value;
            }
            long remaining = remainingMillis();
            if (remaining <= 0) {
                throw timedOut(what);
            }
            Thread.sleep(Math.max(1, Math.min(pollMillis, remaining)));
        }
    }

    /**
     * Fails the step unless {@code later} came after {@code earlier} by bus sequence; records both
     * sequence numbers as facts under {@code earlierName + "Sequence"} and {@code laterName + "Sequence"}.
     */
    public void requireOrder(String earlierName, ClusterEvent earlier, String laterName, ClusterEvent later) {
        fact(earlierName + "Sequence", earlier.sequence());
        fact(laterName + "Sequence", later.sequence());
        if (later.sequence() <= earlier.sequence()) {
            throw fail(later.type() + " (event " + later.sequence() + ") came before " + earlier.type()
                    + " (event " + earlier.sequence() + "), not after it.");
        }
    }

    long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - startNanos);
    }

    private long remainingMillis() {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - nanoClock.getAsLong());
    }

    private StepFailedException timedOut(String what) {
        return fail(String.format(Locale.ROOT, "Waited %.1f s for %s, but it did not happen.",
                elapsedMillis() / 1000.0, what));
    }

    private boolean isForeignReset(ClusterEvent event) {
        return event.sequence() > ownResetSequence && CLUSTER_MODULE.equals(event.module())
                && CLUSTER_RESET.equals(event.type());
    }

    private void checkNoForeignReset(ClusterEvent delivered) {
        ClusterEvent reset = delivered != null ? delivered : bus.query(CLUSTER_MODULE, 0, Integer.MAX_VALUE).stream()
                .filter(this::isForeignReset).findFirst().orElse(null);
        if (reset != null) {
            throw fail("The cluster was reset during the scenario (event " + reset.sequence()
                    + "), so the scenario cannot continue.");
        }
    }

    private static ClusterEvent earlier(ClusterEvent a, ClusterEvent b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.sequence() <= b.sequence() ? a : b;
    }
}
