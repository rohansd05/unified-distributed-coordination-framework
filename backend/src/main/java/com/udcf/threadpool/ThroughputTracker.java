package com.udcf.threadpool;

import com.udcf.config.ThreadPoolProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Sliding-window throughput and latency statistics.
 *
 * <p>Records one sample per completed request and answers questions over the last
 * N seconds only. A cumulative average would flatten out within a minute and make the
 * effect of a load spike invisible, which defeats the purpose of the demonstration.</p>
 *
 * <p>A {@link Clock} is injected so tests can advance time deterministically instead of
 * sleeping.</p>
 */
@Component
public class ThroughputTracker {

    private record Sample(long epochMillis, double durationMillis) {
    }

    private final Deque<Sample> samples = new ArrayDeque<>();
    private final long windowMillis;
    private final Clock clock;

    @Autowired
    public ThroughputTracker(ThreadPoolProperties properties) {
        this(properties.getMetricsWindowSeconds(), Clock.systemUTC());
    }

    /** Package-visible so tests can inject a controllable clock. */
    ThroughputTracker(int windowSeconds, Clock clock) {
        this.windowMillis = Math.max(1, windowSeconds) * 1000L;
        this.clock = clock;
    }

    public synchronized void record(double durationMillis) {
        samples.addLast(new Sample(clock.millis(), durationMillis));
        prune();
    }

    /** Completed requests per second across the window. */
    public synchronized double requestsPerSecond() {
        prune();
        if (samples.isEmpty()) {
            return 0d;
        }
        return round(samples.size() / (windowMillis / 1000d));
    }

    public synchronized double averageResponseTimeMillis() {
        prune();
        if (samples.isEmpty()) {
            return 0d;
        }
        double total = 0d;
        for (Sample sample : samples) {
            total += sample.durationMillis();
        }
        return round(total / samples.size());
    }

    /**
     * 95th percentile using nearest-rank. With few samples this returns the slowest
     * observed request, which is the honest answer rather than an interpolated guess.
     */
    public synchronized double p95ResponseTimeMillis() {
        prune();
        if (samples.isEmpty()) {
            return 0d;
        }
        List<Double> durations = new ArrayList<>(samples.size());
        for (Sample sample : samples) {
            durations.add(sample.durationMillis());
        }
        durations.sort(Double::compare);

        int rank = (int) Math.ceil(0.95d * durations.size());
        int index = Math.min(durations.size() - 1, Math.max(0, rank - 1));
        return round(durations.get(index));
    }

    public synchronized int sampleCount() {
        prune();
        return samples.size();
    }

    public synchronized void clear() {
        samples.clear();
    }

    private void prune() {
        long cutoff = clock.millis() - windowMillis;
        while (!samples.isEmpty() && samples.peekFirst().epochMillis() < cutoff) {
            samples.pollFirst();
        }
    }

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
