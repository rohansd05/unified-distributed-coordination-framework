package com.udcf.modules.matrix;

import com.udcf.core.metrics.MetricNames;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The Experiment 10 meters on real runner results: names, tags, which values are recorded, no unbounded tag. */
class MatrixMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MatrixMetrics metrics = new MatrixMetrics(registry);

    private double runs(String outcome) {
        var counter = registry.find(MetricNames.MATRIX_RUNS_TOTAL).tags(MetricNames.NODE_ID, "1", "outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("COMPLETE: run counted for the root, parallel time, each node's compute (node_id = that node), each phase")
    void complete() {
        metrics.record(MatrixTestResults.complete(1, 10));

        assertThat(runs("COMPLETE")).isEqualTo(1);
        assertThat(registry.find(MetricNames.MATRIX_EXECUTION_DURATION).tags(MetricNames.NODE_ID, "1", "outcome", "COMPLETE")
                .timer().count()).isEqualTo(1);
        for (String node : new String[] {"1", "2", "3"}) {
            assertThat(registry.find(MetricNames.MATRIX_COMPUTE_DURATION).tags(MetricNames.NODE_ID, node).timer().count())
                    .as("node %s", node).isEqualTo(1);
        }
        for (String phase : new String[] {"SCATTER", "BROADCAST", "GATHER"}) {
            assertThat(registry.find(MetricNames.MATRIX_COMMUNICATION_DURATION).tags(MetricNames.NODE_ID, "1", "phase", phase)
                    .timer().count()).as(phase).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("REFUSED: counted, and no timer is recorded (nothing was measured)")
    void refused() {
        metrics.record(MatrixTestResults.refused(1));

        assertThat(runs("REFUSED")).isEqualTo(1);
        assertThat(registry.find(MetricNames.MATRIX_EXECUTION_DURATION).timers()).isEmpty();
        assertThat(registry.find(MetricNames.MATRIX_COMPUTE_DURATION).timers()).isEmpty();
        assertThat(registry.find(MetricNames.MATRIX_COMMUNICATION_DURATION).timers()).isEmpty();
    }

    @Test
    @DisplayName("PARTIAL: only DONE computes are timed; the failed node has no compute timer")
    void partial() {
        metrics.record(MatrixTestResults.partial(1, 10));

        assertThat(runs("PARTIAL")).isEqualTo(1);
        assertThat(registry.find(MetricNames.MATRIX_COMPUTE_DURATION).timers())
                .extracting(timer -> timer.getId().getTag(MetricNames.NODE_ID)).containsExactlyInAnyOrder("1", "2");
    }

    @Test
    @DisplayName("FAILED: no fetch was made, so no GATHER timer (an unknown is never recorded as 0)")
    void unknownPhaseNotRecorded() {
        metrics.record(MatrixTestResults.failed(1, 10));

        assertThat(runs("FAILED")).isEqualTo(1);
        assertThat(registry.find(MetricNames.MATRIX_COMMUNICATION_DURATION).tag("phase", "GATHER").timers()).isEmpty();
        assertThat(registry.find(MetricNames.MATRIX_COMMUNICATION_DURATION).tag("phase", "SCATTER").timers()).hasSize(1);
    }

    @Test
    @DisplayName("tag keys are only node_id, outcome and phase: no size, run id or seed")
    void boundedTags() {
        metrics.record(MatrixTestResults.complete(1, 10));
        metrics.record(MatrixTestResults.partial(2, 12));
        metrics.record(MatrixTestResults.refused(3));

        Set<String> keys = registry.getMeters().stream().map(Meter::getId)
                .filter(id -> id.getName().startsWith("distributed_matrix_"))
                .flatMap(id -> id.getTags().stream()).map(Tag::getKey).collect(Collectors.toSet());
        assertThat(keys).containsExactlyInAnyOrder(MetricNames.NODE_ID, "outcome", "phase");
        assertThat(registry.getMeters()).noneMatch(meter -> meter.getId().getType() == Meter.Type.GAUGE
                && meter.getId().getName().startsWith("distributed_matrix_"));
    }
}
