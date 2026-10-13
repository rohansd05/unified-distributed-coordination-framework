package com.udcf.modules.mpi;

import com.udcf.core.metrics.MetricNames;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Experiment 9 meters: node_id is the root on every meter, the LOCAL part is never counted, and a
 * timer sample only for a delivered transfer or an attempted collective. No socket.
 */
class MpiMetricsTest {

    private SimpleMeterRegistry registry;
    private MpiMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MpiMetrics(registry);
    }

    private double count(String name, String... tags) {
        return registry.find(name).tags(tags).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private long samples(String name, String... tags) {
        return registry.find(name).tags(tags).timers().stream().mapToLong(Timer::count).sum();
    }

    @Test
    @DisplayName("COMPLETE broadcast from node 2: one collective, a DELIVERED message and a latency sample per other node, one duration")
    void completeBroadcast() {
        CollectiveResult result = MpiTestResults.complete(CollectiveOperation.BROADCAST, 2);
        metrics.record(result);

        assertThat(count(MetricNames.MPI_COLLECTIVES_TOTAL, "node_id", "2", "operation", "BROADCAST", "outcome", "COMPLETE"))
                .isEqualTo(1);
        assertThat(count(MetricNames.MPI_MESSAGES_TOTAL, "node_id", "2", "target_node", "1", "direction", "TO_RANK",
                "status", "DELIVERED")).isEqualTo(1);
        assertThat(count(MetricNames.MPI_MESSAGES_TOTAL, "node_id", "2", "target_node", "3", "status", "DELIVERED"))
                .isEqualTo(1);
        assertThat(count(MetricNames.MPI_MESSAGES_TOTAL, "status", "LOCAL")).isZero();
        assertThat(count(MetricNames.MPI_MESSAGES_TOTAL, "target_node", "2")).isZero();
        assertThat(samples(MetricNames.MPI_TRANSFER_LATENCY, "node_id", "2")).isEqualTo(2);
        long latencies = result.transfers().stream().filter(t -> t.latencyNanos() != null)
                .mapToLong(TransferRecord::latencyNanos).sum();
        assertThat(registry.find(MetricNames.MPI_TRANSFER_LATENCY).timers().stream()
                .mapToDouble(t -> t.totalTime(TimeUnit.NANOSECONDS)).sum()).isEqualTo((double) latencies);
        Timer duration = registry.find(MetricNames.MPI_COLLECTIVE_DURATION)
                .tags("node_id", "2", "operation", "BROADCAST", "outcome", "COMPLETE").timer();
        assertThat(duration.count()).isEqualTo(1);
        assertThat(duration.totalTime(TimeUnit.NANOSECONDS)).isEqualTo((double) result.totalNanos());
    }

    @Test
    @DisplayName("PARTIAL: the failed transfer is counted as FAILED and has no latency sample")
    void partial() {
        metrics.record(MpiTestResults.partialLookup(CollectiveOperation.SCATTER));

        assertThat(count(MetricNames.MPI_COLLECTIVES_TOTAL, "outcome", "PARTIAL")).isEqualTo(1);
        assertThat(count(MetricNames.MPI_MESSAGES_TOTAL, "target_node", "3", "status", "FAILED")).isEqualTo(1);
        assertThat(samples(MetricNames.MPI_TRANSFER_LATENCY, "target_node", "3")).isZero();
        assertThat(samples(MetricNames.MPI_TRANSFER_LATENCY, "target_node", "2")).isEqualTo(1);
        assertThat(samples(MetricNames.MPI_COLLECTIVE_DURATION, "outcome", "PARTIAL")).isEqualTo(1);
    }

    @Test
    @DisplayName("gather: direction FROM_RANK and target_node is the rank's node, the one the root called")
    void gather() {
        metrics.record(MpiTestResults.complete(CollectiveOperation.GATHER, 1));

        assertThat(count(MetricNames.MPI_MESSAGES_TOTAL, "node_id", "1", "target_node", "2", "direction", "FROM_RANK",
                "status", "DELIVERED")).isEqualTo(1);
        assertThat(count(MetricNames.MPI_MESSAGES_TOTAL, "target_node", "3", "direction", "FROM_RANK")).isEqualTo(1);
    }

    @Test
    @DisplayName("NOT_ATTEMPTED is counted as a status, without a latency")
    void notAttempted() {
        metrics.record(MpiTestResults.notAttempted());

        assertThat(count(MetricNames.MPI_MESSAGES_TOTAL, "target_node", "3", "status", "NOT_ATTEMPTED")).isEqualTo(1);
        assertThat(samples(MetricNames.MPI_TRANSFER_LATENCY, "target_node", "3")).isZero();
    }

    @Test
    @DisplayName("REFUSED: counted as a collective; no message, no latency, no duration")
    void refused() {
        metrics.record(MpiTestResults.refused(1));

        assertThat(count(MetricNames.MPI_COLLECTIVES_TOTAL, "node_id", "1", "operation", "SCATTER", "outcome", "REFUSED"))
                .isEqualTo(1);
        assertThat(registry.find(MetricNames.MPI_MESSAGES_TOTAL).meters()).isEmpty();
        assertThat(registry.find(MetricNames.MPI_TRANSFER_LATENCY).meters()).isEmpty();
        assertThat(registry.find(MetricNames.MPI_COLLECTIVE_DURATION).meters()).isEmpty();
    }

    @Test
    @DisplayName("every meter carries node_id = the root, and each name always has the same tag keys")
    void nodeIdIsTheRoot() {
        metrics.record(MpiTestResults.complete(CollectiveOperation.BROADCAST, 3));
        metrics.record(MpiTestResults.partialLookup(CollectiveOperation.GATHER));

        for (Meter meter : registry.getMeters()) {
            String root = meter.getId().getTag(MetricNames.NODE_ID);
            assertThat(root).as("node_id of %s", meter.getId()).isIn("1", "3");
        }
        assertThat(registry.find(MetricNames.MPI_MESSAGES_TOTAL).meters()).allSatisfy(m -> assertThat(
                m.getId().getTags()).extracting(t -> t.getKey())
                .containsExactlyInAnyOrder("node_id", "target_node", "operation", "direction", "status"));
    }
}
