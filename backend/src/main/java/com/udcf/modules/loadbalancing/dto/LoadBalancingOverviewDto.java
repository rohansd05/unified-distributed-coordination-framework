package com.udcf.modules.loadbalancing.dto;

import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.loadbalancing.LoadBalancingProperties;

import java.util.List;

/**
 * {@code GET /api/modules/loadbalancing}: everything the Experiment 6 page needs.
 *
 * <p>No dedicated test: a record; the JSON shape is tested in LoadBalancingControllerTest.</p>
 *
 * @param actionInProgress what holds the module while BUSY; null otherwise
 * @param latestRun        null until the first run
 * @param latestComparison null until the first comparison
 */
public record LoadBalancingOverviewDto(
        ModuleStatus status,
        String actionInProgress,
        LoadBalancingProperties.Defaults defaults,
        LoadBalancingProperties.Limits limits,
        String capacityNote,
        String workUnitsNote,
        String deliveryNote,
        String warmUpNote,
        String crashNote,
        List<StrategyDto> strategies,
        List<WorkerDto> workers,
        RunDto latestRun,
        ComparisonDto latestComparison
) {
}
