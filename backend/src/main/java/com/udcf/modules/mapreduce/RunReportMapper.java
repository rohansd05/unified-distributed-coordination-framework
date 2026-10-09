package com.udcf.modules.mapreduce;

import com.udcf.modules.mapreduce.dto.JobReportDto;
import com.udcf.modules.mapreduce.dto.ResultRowDto;
import com.udcf.modules.mapreduce.dto.StageTimingsDto;
import com.udcf.modules.mapreduce.dto.TaskRowDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns one run's {@link JobReport}, results and task rows into a {@link JobReportDto}.
 *
 * <p><b>Null, never 0.</b> A failed run reports only what was measured before it failed: pair
 * counts are {@code null} unless the map stage finished, shuffle keys and partitions unless the
 * shuffle finished, and the result count always. Stage timings stay {@code null} for a stage
 * that did not run (E7a's rule), and the combiner saving is {@code null} when no pairs were
 * emitted.</p>
 *
 * <p><b>Latency.</b> The latency job's reduced value is {@code sum;count}. A row's average is
 * that sum divided by that count, computed once here, and only where the count is above 0;
 * otherwise it is {@code null} and the display keeps the job's "No latency measured" text.
 * No average of averages is ever taken.</p>
 */
final class RunReportMapper {

    private RunReportMapper() {
    }

    /**
     * @param results           the run's results, or {@code null} for a failed run
     * @param inputLinesDropped event-log lines left out, or {@code null} for other inputs
     * @param maxRows           most result rows listed
     */
    static JobReportDto map(MapReduceJob job, JobReport report, Map<String, String> results,
                            List<TaskRowDto> tasks, int reducers, Integer inputLinesDropped, int maxRows) {
        boolean completed = results != null;
        boolean mapDone = completed || report.mapMillis() != null;
        boolean shuffleDone = completed || report.shuffleMillis() != null;

        List<ResultRowDto> rows = new ArrayList<>();
        boolean truncated = false;
        if (completed) {
            for (Map.Entry<String, String> entry : new TreeMap<>(results).entrySet()) {
                if (rows.size() == maxRows) {
                    truncated = true;
                    break;
                }
                rows.add(row(job, entry.getKey(), entry.getValue()));
            }
        }
        return new JobReportDto(
                reducers,
                report.inputLines(),
                inputLinesDropped,
                report.splits(),
                report.mapTasks(),
                mapDone ? report.pairsEmitted() : null,
                mapDone ? report.pairsAfterCombine() : null,
                shuffleDone ? report.shuffleKeys() : null,
                shuffleDone ? report.reduceTasks() : null,
                completed ? results.size() : null,
                report.combinerSavingPercent(),
                report.failedTasksRetried(),
                new StageTimingsDto(report.mapMillis(), report.shuffleMillis(), report.reduceMillis(),
                        completed ? report.totalMillis() : null),
                report.mapTasksPerNode(),
                report.reduceTasksPerNode(),
                List.copyOf(tasks),
                List.copyOf(rows),
                truncated);
    }

    /** One result row (see the class description for the latency job). */
    static ResultRowDto row(MapReduceJob job, String key, String value) {
        String display = job.formatResult(key, value);
        if (job instanceof LatencyPerNodeJob) {
            String[] parts = value == null ? new String[0] : value.split(";");
            if (parts.length < 2) {
                return new ResultRowDto(key, display, null, null);
            }
            try {
                double sum = Double.parseDouble(parts[0]);
                long count = Long.parseLong(parts[1]);
                Double average = count > 0 ? Math.round(sum / count * 100.0) / 100.0 : null;
                return new ResultRowDto(key, display, count, average);
            } catch (NumberFormatException e) {
                return new ResultRowDto(key, display, null, null);
            }
        }
        try {
            return new ResultRowDto(key, display, value == null ? null : Long.parseLong(value), null);
        } catch (NumberFormatException e) {
            return new ResultRowDto(key, display, null, null);
        }
    }
}
