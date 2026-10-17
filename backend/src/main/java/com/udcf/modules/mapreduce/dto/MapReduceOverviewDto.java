package com.udcf.modules.mapreduce.dto;

import com.udcf.core.module.ModuleStatus;

import java.util.List;

/**
 * {@code GET /api/modules/mapreduce}: what the page needs to offer a run.
 *
 * <p>No dedicated test: a record; tested through MapReduceModuleTest and MapReduceControllerTest.</p>
 *
 * @param currentAction the running action, or {@code null} when none
 * @param coordinatorId the leader elected by Experiment 4, which coordinates the next run, or
 *                      {@code null} when there is no leader yet (reading this never starts an election)
 * @param workerIds     the live nodes that would be workers now (empty when none)
 * @param latestRun     the most recent run, or {@code null} when none is kept
 */
public record MapReduceOverviewDto(
        ModuleStatus status,
        String currentAction,
        List<JobDto> jobs,
        List<InputTypeDto> inputTypes,
        Integer coordinatorId,
        List<Integer> workerIds,
        MapReduceLimitsDto limits,
        RunDto latestRun,
        List<String> notes
) {
}
