package com.udcf.modules.faulttolerance;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.core.metrics.MetricNames;
import com.udcf.modules.election.ElectionAlgorithm;
import com.udcf.modules.election.ElectionMetrics;
import com.udcf.modules.election.ElectionRound;
import com.udcf.modules.election.RoundOutcome;
import com.udcf.modules.election.RoundTrigger;
import com.udcf.modules.replication.ApplyResult;
import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.modules.replication.PushOutcome;
import com.udcf.modules.replication.ReplicationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Experiment 8 meters: {@code node_id} on every meter (R5), only measured intervals recorded
 * (R7), and no tag-key clash with the Experiment 4 and 5 meters in one Prometheus registry.
 */
class FaultToleranceMetricsTest {

    private static final long MS = 1_000_000L;

    /** A run with every instant: crash 0, detected 2000, elected 2010, promoted 2100, restored 2300, recovered 5000, demoted 5100, resynced 5200 ms. */
    private static FailoverRun completeRun() {
        return new FailoverRun(1, 1, 2, 0L, InstantSource.ACTION, 2000 * MS, 3, 2010 * MS, 2, 3L, 2100 * MS,
                2300 * MS, 5000 * MS, 5100 * MS, 5200 * MS, FailoverRun.Outcome.RESTORED);
    }

    @Test
    @DisplayName("registers beside the Experiment 4 and 5 meters in one Prometheus registry without a tag-key clash")
    void coexistsWithElectionAndReplicationMeters() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ClusterEventBus bus = new ClusterEventBus(new EventProperties(100, 100), Clock.systemUTC());
        try (Cluster cluster = new Cluster(new ClusterProperties(3, Collections.nCopies(3, NodeCapacity.FAST),
                FailoverFixture.PORTS), bus)) {
            assertThatCode(() -> {
                new ElectionMetrics(registry).record(new ElectionRound(1, ElectionAlgorithm.BULLY,
                        RoundTrigger.LEADER_FAILURE, 2, Instant.EPOCH, RoundOutcome.ELECTED, 3, 12.5));
                ReplicationMetrics replication = new ReplicationMetrics(registry, cluster);
                replication.recordWrite(1, ConsistencyModel.SYNCHRONOUS);
                replication.recordPush(PushOutcome.acked(2, ApplyResult.APPLIED, 2, 1.5), "SYNCHRONOUS");
                FaultToleranceMetrics metrics = new FaultToleranceMetrics(registry);
                metrics.failureDetected(1);
                metrics.runRestored(completeRun());
                metrics.oldPrimaryRecovered(completeRun());
            }).doesNotThrowAnyException();

            String scrape = registry.scrape();
            assertThat(scrape).contains("distributed_failures_total{node_id=\"1\"} 1.0");
            assertThat(scrape).contains("distributed_recovery_duration_seconds_count{interval=\"outage\",node_id=\"1\"} 1");
            assertThat(scrape).contains("distributed_recovery_duration_seconds_count{interval=\"recovery\",node_id=\"1\"} 1");
            assertThat(scrape).contains("distributed_election_duration_seconds_count");
            assertThat(scrape).contains("distributed_replication_writes_total");
        } finally {
            bus.close();
        }
    }

    @Test
    @DisplayName("each interval is recorded with node_id = the failed primary, in milliseconds as measured")
    void recordsEveryMeasuredInterval() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FaultToleranceMetrics metrics = new FaultToleranceMetrics(registry);

        metrics.runRestored(completeRun());
        metrics.oldPrimaryRecovered(completeRun());

        assertThat(timerMillis(registry, "detection")).isEqualTo(2000.0);
        assertThat(timerMillis(registry, "failover")).isEqualTo(100.0);
        assertThat(timerMillis(registry, "service_restored")).isEqualTo(200.0);
        assertThat(timerMillis(registry, "outage")).isEqualTo(2300.0);
        assertThat(timerMillis(registry, "recovery")).isEqualTo(200.0);
    }

    @Test
    @DisplayName("an interval that never completed records nothing (never 0)")
    void recordsNothingUnmeasured() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FaultToleranceMetrics metrics = new FaultToleranceMetrics(registry);
        FailoverRun detectedOnly = new FailoverRun(1, 1, 2, null, null, 2000 * MS, 3, null, null, null, null, null,
                null, null, null, FailoverRun.Outcome.IN_PROGRESS);

        metrics.runRestored(detectedOnly);
        metrics.oldPrimaryRecovered(detectedOnly);

        assertThat(registry.find(MetricNames.RECOVERY_DURATION).timers()).isEmpty();
    }

    private static double timerMillis(SimpleMeterRegistry registry, String interval) {
        return registry.get(MetricNames.RECOVERY_DURATION).tag(MetricNames.NODE_ID, "1")
                .tag(FaultToleranceMetrics.INTERVAL, interval).timer()
                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}
