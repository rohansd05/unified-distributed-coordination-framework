package com.udcf.modules.rmi;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Measures how long an action takes, whether it returns or throws, from an injected nanosecond
 * time source ({@code System::nanoTime} in production, a fake in tests).
 *
 * <p>A time source that goes backwards is an {@link IllegalStateException}: an elapsed time is
 * never clamped or invented (R7).</p>
 */
public final class CallTimer {

    /** An action to time; it may throw any exception. */
    @FunctionalInterface
    public interface TimedAction<T> {
        T run() throws Exception;
    }

    /**
     * The outcome of one timed action: its value, or the exception it threw (never both).
     *
     * @param value        what the action returned; null if it threw
     * @param failure      what the action threw; null if it returned
     * @param elapsedNanos measured from just before the action to just after it returned or threw
     */
    public record Measurement<T>(T value, Exception failure, long elapsedNanos) {

        public Measurement {
            if (value != null && failure != null) {
                throw new IllegalArgumentException("a measurement has a value or a failure, not both");
            }
            if (elapsedNanos < 0) {
                throw new IllegalArgumentException("elapsedNanos must be >= 0, was " + elapsedNanos);
            }
        }

        public boolean failed() {
            return failure != null;
        }
    }

    private final LongSupplier nanoClock;

    public CallTimer(LongSupplier nanoClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
    }

    /**
     * Runs {@code action} once and times it. An {@link Exception} it throws is captured in the
     * result; an {@link Error} propagates.
     */
    public <T> Measurement<T> measure(TimedAction<T> action) {
        Objects.requireNonNull(action, "action must not be null");
        long start = nanoClock.getAsLong();
        T value = null;
        Exception failure = null;
        try {
            value = action.run();
        } catch (Exception e) {
            failure = e;
        }
        long elapsed = nanoClock.getAsLong() - start;
        if (elapsed < 0) {
            throw new IllegalStateException("time source went backwards by " + (-elapsed) + " ns");
        }
        return new Measurement<>(value, failure, elapsed);
    }
}
