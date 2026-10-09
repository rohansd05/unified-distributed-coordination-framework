package com.udcf.modules.mapreduce;

import com.udcf.modules.mapreduce.dto.FailedAttemptDto;
import com.udcf.modules.mapreduce.dto.TaskRowDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects every task attempt of one run (through the pipeline's
 * {@link MapReducePipeline.TaskAttemptListener}) into one row per task: the worker that
 * finally ran it, the number of attempts and each failed attempt. Thread-safe: attempts of
 * different tasks arrive on different pipeline threads.
 */
final class TaskAttemptRecorder implements MapReducePipeline.TaskAttemptListener {

    /** One failed attempt, for the metrics. */
    record Failure(TaskType taskType, int workerId) {
    }

    private record TaskKey(TaskType type, int index) {
    }

    private static final class Row {
        private int attempts;
        private Integer workerId;
        private final List<FailedAttemptDto> failed = new ArrayList<>();
    }

    private final Map<TaskKey, Row> rows = new HashMap<>();

    @Override
    public synchronized void onAttempt(TaskType type, int taskIndex, int attempt, int nodeId, String failureReason) {
        Row row = rows.computeIfAbsent(new TaskKey(type, taskIndex), key -> new Row());
        row.attempts = Math.max(row.attempts, attempt);
        if (failureReason == null) {
            row.workerId = nodeId;
        } else {
            row.failed.add(new FailedAttemptDto(attempt, nodeId, failureReason));
        }
    }

    /** One row per task: map tasks first, then reduce tasks, each by task number. */
    synchronized List<TaskRowDto> rows() {
        return rows.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<TaskKey, Row> e) -> e.getKey().type())
                        .thenComparingInt(e -> e.getKey().index()))
                .map(e -> {
                    Row row = e.getValue();
                    List<FailedAttemptDto> failed = row.failed.stream()
                            .sorted(Comparator.comparingInt(FailedAttemptDto::attempt)).toList();
                    return new TaskRowDto(e.getKey().type().name(), e.getKey().index() + 1,
                            row.workerId != null, row.workerId, row.attempts, failed);
                })
                .toList();
    }

    /** Every failed attempt, by task type and worker. */
    synchronized List<Failure> failures() {
        List<Failure> failures = new ArrayList<>();
        rows.forEach((key, row) -> row.failed.forEach(f -> failures.add(new Failure(key.type(), f.workerId()))));
        return failures;
    }
}
