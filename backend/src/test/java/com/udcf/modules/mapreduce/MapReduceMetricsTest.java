package com.udcf.modules.mapreduce;

import com.udcf.core.metrics.MetricNames;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Experiment 7 meters: values, node_id on every meter, and nothing recorded that was not measured. */
class MapReduceMetricsTest {

    @Test
    @DisplayName("a completed run counts tasks per worker, the run per coordinator, failed attempts and the duration")
    void completedRun() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MapReduceMetrics metrics = new MapReduceMetrics(registry);

        metrics.recordRun("word-count", 1, true, Map.of(1, 2, 2, 1), Map.of(3, 1), 12.5,
                List.of(new TaskAttemptRecorder.Failure(TaskType.MAP, 2)));

        assertThat(registry.get(MetricNames.MAP_TASKS_TOTAL).tags(MetricNames.NODE_ID, "1", "job", "word-count")
                .counter().count()).isEqualTo(2.0);
        assertThat(registry.get(MetricNames.REDUCE_TASKS_TOTAL).tags(MetricNames.NODE_ID, "3").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(MetricNames.MAPREDUCE_JOBS_TOTAL)
                .tags(MetricNames.NODE_ID, "1", "job", "word-count", "outcome", "completed").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(MetricNames.MAPREDUCE_TASK_ATTEMPTS_FAILED_TOTAL)
                .tags(MetricNames.NODE_ID, "2", "task_type", "map").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(MetricNames.MAPREDUCE_JOB_DURATION).tags(MetricNames.NODE_ID, "1").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(12.5);
        for (Meter meter : registry.getMeters()) {
            assertThat(meter.getId().getTag(MetricNames.NODE_ID)).as(meter.getId().getName()).isNotNull();
        }
    }

    @Test
    @DisplayName("a failed run counts as failed and records no duration")
    void failedRun() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new MapReduceMetrics(registry).recordRun("avg-latency-per-node", 2, false, Map.of(), Map.of(), null, List.of());

        assertThat(registry.get(MetricNames.MAPREDUCE_JOBS_TOTAL).tags("outcome", "failed").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find(MetricNames.MAPREDUCE_JOB_DURATION).timer()).isNull();
        assertThat(registry.find(MetricNames.MAP_TASKS_TOTAL).counter()).isNull();
    }

    @Test
    @DisplayName("two instances on one registry add to the same meters")
    void sharedMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MapReduceMetrics(registry).recordRun("word-count", 1, true, Map.of(1, 1), Map.of(), 1.0, List.of());
        new MapReduceMetrics(registry).recordRun("word-count", 1, true, Map.of(1, 1), Map.of(), 1.0, List.of());

        assertThat(registry.get(MetricNames.MAP_TASKS_TOTAL).counter().count()).isEqualTo(2.0);
        assertThat(registry.get(MetricNames.MAPREDUCE_JOB_DURATION).timer().count()).isEqualTo(2);
    }
}
