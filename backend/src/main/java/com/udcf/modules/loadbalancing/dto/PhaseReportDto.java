package com.udcf.modules.loadbalancing.dto;

import com.udcf.modules.loadbalancing.Strategy;

import java.util.List;

/**
 * The measured result of one run. Every figure is measured; a latency with nothing to
 * measure is null, never 0. Milliseconds are rounded to 2 decimals.
 *
 * <p>No dedicated test: a record; built and tested through LoadBalancingModuleTest.</p>
 */
public record PhaseReportDto(
        String runId,
        Strategy strategy,
        int requestCount,
        long served,
        long failures,
        long reroutes,
        double makespanMillis,
        Double averageLatencyMillis,
        Double p95LatencyMillis,
        Double maxLatencyMillis,
        int loadSpread,
        List<NodeResultDto> nodes
) {
}
