package com.udcf.modules.loadbalancing.dto;

import com.udcf.modules.loadbalancing.Strategy;

import java.time.Instant;

/**
 * One run of one strategy: returned by {@code POST /runs} (RUNNING) and shown as the
 * overview's {@code latestRun}.
 *
 * <p>No dedicated test: a record; tested through LoadBalancingModuleTest and
 * LoadBalancingControllerTest.</p>
 *
 * @param crash  null for a run without a crash plan
 * @param report null until FINISHED
 * @param error  set only when FAILED
 */
public record RunDto(
        String runId,
        ActionState state,
        Strategy strategy,
        int requestCount,
        int workUnits,
        int concurrency,
        CrashDto crash,
        Instant startedAt,
        Instant finishedAt,
        PhaseReportDto report,
        String error
) {
}
