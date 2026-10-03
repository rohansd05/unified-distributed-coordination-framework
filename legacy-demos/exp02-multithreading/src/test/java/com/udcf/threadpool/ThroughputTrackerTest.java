package com.udcf.threadpool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ThroughputTrackerTest {

    /** Test clock we can advance by hand, so no test ever has to sleep. */
    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Test
    @DisplayName("reports zero on an empty window instead of dividing by zero")
    void reportsZeroWhenEmpty() {
        ThroughputTracker tracker = new ThroughputTracker(10, new MutableClock());

        assertThat(tracker.requestsPerSecond()).isZero();
        assertThat(tracker.averageResponseTimeMillis()).isZero();
        assertThat(tracker.p95ResponseTimeMillis()).isZero();
        assertThat(tracker.sampleCount()).isZero();
    }

    @Test
    @DisplayName("throughput is samples in the window divided by window length")
    void computesThroughput() {
        ThroughputTracker tracker = new ThroughputTracker(10, new MutableClock());

        for (int i = 0; i < 20; i++) {
            tracker.record(5d);
        }

        assertThat(tracker.requestsPerSecond()).isEqualTo(2.0d);
    }

    @Test
    @DisplayName("averages only the durations still inside the window")
    void averagesWithinWindow() {
        ThroughputTracker tracker = new ThroughputTracker(10, new MutableClock());

        tracker.record(10d);
        tracker.record(20d);

        assertThat(tracker.averageResponseTimeMillis()).isEqualTo(15.0d);
    }

    @Test
    @DisplayName("drops samples that fall outside the sliding window")
    void dropsExpiredSamples() {
        MutableClock clock = new MutableClock();
        ThroughputTracker tracker = new ThroughputTracker(10, clock);

        tracker.record(100d);
        clock.advanceSeconds(11);
        tracker.record(20d);

        assertThat(tracker.sampleCount()).isEqualTo(1);
        assertThat(tracker.averageResponseTimeMillis()).isEqualTo(20.0d);
    }

    @Test
    @DisplayName("p95 uses nearest rank and returns the slowest request for small samples")
    void computesP95() {
        ThroughputTracker tracker = new ThroughputTracker(60, new MutableClock());

        for (int i = 1; i <= 100; i++) {
            tracker.record(i);
        }

        assertThat(tracker.p95ResponseTimeMillis()).isEqualTo(95.0d);
    }

    @Test
    @DisplayName("clear empties the window")
    void clearEmptiesWindow() {
        ThroughputTracker tracker = new ThroughputTracker(10, new MutableClock());
        tracker.record(5d);

        tracker.clear();

        assertThat(tracker.sampleCount()).isZero();
    }
}
