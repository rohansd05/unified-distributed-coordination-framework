package com.udcf.modules.mapreduce.dto;

import com.udcf.modules.mapreduce.InputType;

import java.time.Instant;
import java.util.List;

/**
 * One MapReduce run: returned by {@code POST /runs} (RUNNING), {@code GET /runs/{id}},
 * {@code GET /runs/latest} and as the overview's {@code latestRun}.
 *
 * <p>No dedicated test: a record; tested through MapReduceModuleTest and MapReduceControllerTest.</p>
 *
 * @param inputName     a safe display name: "Bundled sample text", the upload's file name
 *                      (last path segment only) or "Live cluster event log"
 * @param inputBytes    UTF-8 size of the input; {@code null} for an event-log run until its
 *                      snapshot is taken
 * @param coordinatorId the coordinating node (lowest live id, TODO(L1))
 * @param workerIds     planned workers while RUNNING; the workers actually used once finished
 * @param crash         {@code null} for a run without a crash plan
 * @param finishedAt    {@code null} while RUNNING
 * @param report        {@code null} while RUNNING; for a FAILED run, what was measured before it failed
 * @param error         set only when FAILED
 * @param notice        plain sentences about the input, the crash or an empty result; {@code null} when none
 */
public record RunDto(
        String runId,
        RunState state,
        String jobId,
        String jobTitle,
        InputType inputType,
        String inputName,
        Long inputBytes,
        Integer coordinatorId,
        List<Integer> workerIds,
        CrashDto crash,
        Instant startedAt,
        Instant finishedAt,
        JobReportDto report,
        String error,
        String notice
) {
}
