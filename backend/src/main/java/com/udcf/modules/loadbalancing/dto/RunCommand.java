package com.udcf.modules.loadbalancing.dto;

import com.udcf.modules.loadbalancing.Strategy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Request body of {@code POST /api/modules/loadbalancing/runs}. The counts are checked
 * against the configured limits by the module (a missing count arrives as 0 and is refused).
 *
 * <p>No dedicated test: a record; validation is tested in LoadBalancingControllerTest.</p>
 *
 * @param crash optional; null for a run without a crash
 */
public record RunCommand(
        @NotNull(message = "strategy is required") Strategy strategy,
        int requestCount,
        int workUnits,
        int concurrency,
        @Valid CrashPlan crash
) {
}
