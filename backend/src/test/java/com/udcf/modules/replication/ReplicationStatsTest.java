package com.udcf.modules.replication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ReplicationStatsTest {

    private static final Instant T0 = Instant.parse("2026-10-08T10:00:00Z");

    @Test
    @DisplayName("before any ack, averages and max are empty (never 0) and there is no last sync")
    void emptyStateHasNoAveragesNoLastSync() {
        ReplicationStatsSnapshot s = new ReplicationStats(2).snapshot();
        assertThat(s.backupNodeId()).isEqualTo(2);
        assertThat(s.acks()).isZero();
        assertThat(s.failures()).isZero();
        assertThat(s.averageLatencyMillis()).isEmpty();
        assertThat(s.maxLatencyMillis()).isEmpty();
        assertThat(s.lastSync()).isEmpty();
    }

    @Test
    @DisplayName("one ack gives that latency as average and max, and its time as last sync")
    void oneSample() {
        ReplicationStats stats = new ReplicationStats(2);
        stats.recordAck(ApplyResult.APPLIED, 1.25, T0);
        ReplicationStatsSnapshot s = stats.snapshot();
        assertThat(s.acks()).isEqualTo(1);
        assertThat(s.applied()).isEqualTo(1);
        assertThat(s.averageLatencyMillis()).hasValue(1.25);
        assertThat(s.maxLatencyMillis()).hasValue(1.25);
        assertThat(s.lastSync()).contains(T0);
    }

    @Test
    @DisplayName("many acks: latency over every outcome, per-outcome counts, last sync is the latest time")
    void manySamplesAvgMaxLatestLastSync() {
        ReplicationStats stats = new ReplicationStats(3);
        stats.recordAck(ApplyResult.APPLIED, 2.0, T0.plusMillis(30));
        stats.recordAck(ApplyResult.DUPLICATE, 4.0, T0.plusMillis(10));     // arrived out of order
        stats.recordAck(ApplyResult.STALE, 6.0, T0.plusMillis(20));
        stats.recordAck(ApplyResult.STALE_EPOCH, 8.0, T0);
        ReplicationStatsSnapshot s = stats.snapshot();
        assertThat(s.acks()).isEqualTo(4);
        assertThat(List.of(s.applied(), s.duplicates(), s.staleRejections(), s.staleEpochRejections()))
                .containsExactly(1L, 1L, 1L, 1L);
        assertThat(s.averageLatencyMillis().getAsDouble()).isCloseTo(5.0, within(1e-9));
        assertThat(s.maxLatencyMillis()).hasValue(8.0);
        assertThat(s.lastSync()).contains(T0.plusMillis(30));
    }

    @Test
    @DisplayName("every ack is a measured round trip: latency and last sync are recorded whatever the outcome")
    void latencyAndLastSyncRecordedForEveryOutcome() {
        for (ApplyResult outcome : ApplyResult.values()) {
            ReplicationStats stats = new ReplicationStats(2);
            stats.recordAck(outcome, 3.5, T0);
            ReplicationStatsSnapshot s = stats.snapshot();
            assertThat(s.acks()).as("%s", outcome).isEqualTo(1);
            assertThat(s.averageLatencyMillis()).as("%s", outcome).hasValue(3.5);
            assertThat(s.maxLatencyMillis()).as("%s", outcome).hasValue(3.5);
            assertThat(s.lastSync()).as("%s", outcome).contains(T0);
        }
    }

    @Test
    @DisplayName("staleRejections counts STALE only, not duplicates or stale-epoch refusals")
    void staleCountsOnlyStale() {
        ReplicationStats stats = new ReplicationStats(2);
        stats.recordAck(ApplyResult.DUPLICATE, 1, T0);
        stats.recordAck(ApplyResult.DUPLICATE, 1, T0);
        stats.recordAck(ApplyResult.STALE_EPOCH, 1, T0);
        assertThat(stats.snapshot().staleRejections()).isZero();
        stats.recordAck(ApplyResult.STALE, 1, T0);
        assertThat(stats.snapshot().staleRejections()).isEqualTo(1);
    }

    @Test
    @DisplayName("failures are counted and do not affect latency or last sync")
    void failuresCounted() {
        ReplicationStats stats = new ReplicationStats(2);
        stats.recordFailure();
        stats.recordFailure();
        ReplicationStatsSnapshot s = stats.snapshot();
        assertThat(s.failures()).isEqualTo(2);
        assertThat(s.acks()).isZero();
        assertThat(s.averageLatencyMillis()).isEmpty();
        assertThat(s.lastSync()).isEmpty();
    }

    @Test
    @DisplayName("NaN, infinities, negative latencies and latencies above the cap are rejected")
    void rejectsNaNInfinityNegativeAndOverMax() {
        ReplicationStats stats = new ReplicationStats(2);
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.001,
                ReplicationStats.MAX_LATENCY_MILLIS + 1}) {
            assertThatThrownBy(() -> stats.recordAck(ApplyResult.APPLIED, bad, T0)).as("latency %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        stats.recordAck(ApplyResult.APPLIED, 0, T0);
        stats.recordAck(ApplyResult.APPLIED, ReplicationStats.MAX_LATENCY_MILLIS, T0);
        assertThat(stats.snapshot().acks()).isEqualTo(2);
    }

    @Test
    @DisplayName("a null outcome or time is rejected and records nothing")
    void rejectsNullInstantAndOutcome() {
        ReplicationStats stats = new ReplicationStats(2);
        assertThatThrownBy(() -> stats.recordAck(null, 1, T0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> stats.recordAck(ApplyResult.APPLIED, 1, null)).isInstanceOf(NullPointerException.class);
        assertThat(stats.snapshot().acks()).isZero();
    }

    @Test
    @DisplayName("reset returns to the empty state")
    void reset() {
        ReplicationStats stats = new ReplicationStats(2);
        stats.recordAck(ApplyResult.STALE, 3, T0);
        stats.recordFailure();
        stats.reset();
        ReplicationStatsSnapshot s = stats.snapshot();
        assertThat(s.acks() + s.failures()).isZero();
        assertThat(s.averageLatencyMillis()).isEmpty();
        assertThat(s.maxLatencyMillis()).isEmpty();
        assertThat(s.lastSync()).isEmpty();
    }

    @Test
    @DisplayName("8 threads x 1000 records each, started together, give exact counts and sums")
    void concurrentRecordingWithBarrierExactCounts() throws Exception {
        ReplicationStats stats = new ReplicationStats(2);
        int threads = 8;
        int each = 1000;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                futures.add(pool.submit(() -> {
                    start.await(20, TimeUnit.SECONDS);
                    for (int i = 0; i < each; i++) {
                        ApplyResult outcome = ApplyResult.values()[i % 4];
                        stats.recordAck(outcome, 2.0, T0.plusMillis(thread * each + i));
                        if (i % 10 == 0) {
                            stats.recordFailure();
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        ReplicationStatsSnapshot s = stats.snapshot();
        assertThat(s.acks()).isEqualTo((long) threads * each);
        assertThat(List.of(s.applied(), s.duplicates(), s.staleRejections(), s.staleEpochRejections()))
                .containsOnly((long) threads * each / 4);
        assertThat(s.failures()).isEqualTo((long) threads * (each / 10));
        assertThat(s.averageLatencyMillis()).hasValue(2.0);
        assertThat(s.lastSync()).contains(T0.plusMillis((long) threads * each - 1));
    }

    @Test
    @DisplayName("the backup node id must be at least 1")
    void validatesBackupNodeId() {
        assertThatThrownBy(() -> new ReplicationStats(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ReplicationStats(5).backupNodeId()).isEqualTo(5);
    }
}
