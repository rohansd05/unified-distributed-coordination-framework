package com.udcf.modules.mapreduce.dto;

import com.udcf.modules.mapreduce.InputType;

import java.time.Instant;

/**
 * One line of the run history ({@code GET /runs}, newest first).
 *
 * <p>No dedicated test: a record; tested through MapReduceModuleTest and MapReduceControllerTest.</p>
 *
 * @param finishedAt    {@code null} while RUNNING
 * @param totalMillis   {@code null} unless COMPLETED
 * @param resultKeys    {@code null} unless COMPLETED
 * @param retriedTasks  {@code null} while RUNNING
 * @param crashWorkerId {@code null} for a run without a crash plan
 */
public record RunSummaryDto(
        String runId,
        RunState state,
        String jobId,
        InputType inputType,
        String inputName,
        Instant startedAt,
        Instant finishedAt,
        Double totalMillis,
        Integer resultKeys,
        Integer retriedTasks,
        Integer crashWorkerId
) {
}
