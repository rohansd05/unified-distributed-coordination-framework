package com.udcf.modules.mapreduce.dto;

import com.udcf.core.module.ModuleStatus;

import java.util.List;

/**
 * {@code GET /api/modules/mapreduce}: what the page needs to offer a run.
 *
 * <p>No dedicated test: a record; tested through MapReduceModuleTest and MapReduceControllerTest.</p>
 *
 * @param currentAction the running action, or {@code null} when none
 * @param coordinatorId the node that would coordinate a run now (lowest live id, TODO(L1)),
 *                      or {@code null} when every node is crashed
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
