package com.udcf.scenario.scenarios;

import com.udcf.modules.faulttolerance.dto.SplitBrainDto;
import com.udcf.modules.faulttolerance.dto.ViolationDto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Samples Experiment 8's no-two-primaries check on its own daemon thread, every interval, until
 * stopped: how many samples, the most live primaries seen at once, and every distinct violation.
 * A sample that throws is counted as an error and sampling goes on.
 */
final class SplitBrainSampler {

    static final String THREAD = "udcf-scenario-sampler";
    private static final long JOIN_MILLIS = 5000;

    /** What the sampler saw; {@code violations} are the served messages, in order of first sight. */
    record Summary(int samples, int maxLivePrimaries, List<String> violations, int errors, long elapsedMillis) {
    }

    private final Supplier<SplitBrainDto> check;
    private final long intervalMillis;
    private final LongSupplier nanoClock;
    private final Object lock = new Object();
    private final Thread thread;

    // guarded by lock
    private int samples;
    private int maxLivePrimaries;
    private final Set<String> violations = new LinkedHashSet<>();
    private int errors;
    private long startNanos;
    private long stopNanos = -1;

    private volatile boolean running = true;

    SplitBrainSampler(Supplier<SplitBrainDto> check, long intervalMillis, LongSupplier nanoClock) {
        this.check = Objects.requireNonNull(check, "check must not be null");
        this.intervalMillis = intervalMillis;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        this.thread = new Thread(this::loop, THREAD);
        this.thread.setDaemon(true);
    }

    void start() {
        synchronized (lock) {
            startNanos = nanoClock.getAsLong();
        }
        thread.start();
    }

    /** Stops sampling and waits for the thread; idempotent. */
    Summary stop() {
        running = false;
        thread.interrupt();
        try {
            thread.join(JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (lock) {
            if (stopNanos < 0) {
                stopNanos = nanoClock.getAsLong();
            }
        }
        return summary();
    }

    Summary summary() {
        synchronized (lock) {
            long end = stopNanos >= 0 ? stopNanos : nanoClock.getAsLong();
            return new Summary(samples, maxLivePrimaries, List.copyOf(new ArrayList<>(violations)), errors,
                    TimeUnit.NANOSECONDS.toMillis(end - startNanos));
        }
    }

    /** One sample (the loop calls it; package-visible for tests). */
    void sample() {
        SplitBrainDto report;
        try {
            report = check.get();
        } catch (RuntimeException e) {
            synchronized (lock) {
                errors++;
            }
            return;
        }
        synchronized (lock) {
            samples++;
            maxLivePrimaries = Math.max(maxLivePrimaries, report.livePrimaries().size());
            for (ViolationDto violation : report.violations()) {
                violations.add(violation.message());
            }
        }
    }

    private void loop() {
        while (running) {
            sample();
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
