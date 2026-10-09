package com.udcf.modules.mapreduce;

import com.udcf.core.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Experiment 7 meters, each tagged with {@code node_id} (R5). Every name below is used only
 * here, always with the same tag keys:
 * <ul>
 *   <li>{@value MetricNames#MAP_TASKS_TOTAL} {node_id = worker, job}: completed map tasks.</li>
 *   <li>{@value MetricNames#REDUCE_TASKS_TOTAL} {node_id = worker, job}: completed reduce tasks.</li>
 *   <li>{@value MetricNames#MAPREDUCE_JOBS_TOTAL} {node_id = coordinator, job, outcome
 *       completed|failed}: runs.</li>
 *   <li>{@value MetricNames#MAPREDUCE_JOB_DURATION} {node_id = coordinator, job}: total time of
 *       each completed run; a failed run records nothing here.</li>
 *   <li>{@value MetricNames#MAPREDUCE_TASK_ATTEMPTS_FAILED_TOTAL} {node_id = worker, task_type
 *       map|reduce}: failed task attempts.</li>
 * </ul>
 *
 * <p>Only real values are recorded: a meter appears when something was counted or measured.
 * Plain class, owned by {@link MapReduceModule}; counters and timers are shared by name and
 * tags, so a second instance on the same registry adds to the same meters.</p>
 */
public class MapReduceMetrics {

    static final String JOB = "job";
    static final String OUTCOME = "outcome";
    static final String TASK_TYPE = "task_type";

    private final MeterRegistry registry;

    public MapReduceMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * Adds one finished run, completed or failed.
     *
     * @param totalMillis the measured total time of a completed run; {@code null} for a failed
     *                    run (nothing is recorded in the duration timer then)
     */
    public void recordRun(String jobId, int coordinatorId, boolean completed,
                          Map<Integer, Integer> mapTasksPerNode, Map<Integer, Integer> reduceTasksPerNode,
                          Double totalMillis, List<TaskAttemptRecorder.Failure> failures) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        String coordinator = String.valueOf(coordinatorId);
        mapTasksPerNode.forEach((node, count) -> registry.counter(MetricNames.MAP_TASKS_TOTAL,
                MetricNames.NODE_ID, String.valueOf(node), JOB, jobId).increment(count));
        reduceTasksPerNode.forEach((node, count) -> registry.counter(MetricNames.REDUCE_TASKS_TOTAL,
                MetricNames.NODE_ID, String.valueOf(node), JOB, jobId).increment(count));
        for (TaskAttemptRecorder.Failure failure : failures) {
            registry.counter(MetricNames.MAPREDUCE_TASK_ATTEMPTS_FAILED_TOTAL,
                    MetricNames.NODE_ID, String.valueOf(failure.workerId()),
                    TASK_TYPE, failure.taskType().name().toLowerCase(Locale.ROOT)).increment();
        }
        registry.counter(MetricNames.MAPREDUCE_JOBS_TOTAL, MetricNames.NODE_ID, coordinator, JOB, jobId,
                OUTCOME, completed ? "completed" : "failed").increment();
        if (completed && totalMillis != null) {
            Timer.builder(MetricNames.MAPREDUCE_JOB_DURATION)
                    .description("Total time of a completed MapReduce run")
                    .tag(MetricNames.NODE_ID, coordinator)
                    .tag(JOB, jobId)
                    .register(registry)
                    .record(Duration.ofNanos(Math.round(totalMillis * 1_000_000d)));
        }
    }
}
