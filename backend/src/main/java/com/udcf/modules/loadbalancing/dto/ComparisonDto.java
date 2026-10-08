package com.udcf.modules.loadbalancing.dto;

import java.time.Instant;
import java.util.List;

/**
 * "Compare all four": the same batch run once per strategy, in Strategy order, after one
 * unreported warm-up batch of {@code warmUpRequests} requests.
 *
 * <p>No dedicated test: a record; tested through LoadBalancingModuleTest and
 * LoadBalancingControllerTest.</p>
 *
 * @param phases  the finished phases so far, in run order
 * @param finding null until FINISHED
 * @param error   set only when FAILED
 */
public record ComparisonDto(
        String comparisonId,
        ActionState state,
        int requestCount,
        int workUnits,
        int concurrency,
        int warmUpRequests,
        Instant startedAt,
        Instant finishedAt,
        List<PhaseReportDto> phases,
        FindingDto finding,
        String error
) {
}
