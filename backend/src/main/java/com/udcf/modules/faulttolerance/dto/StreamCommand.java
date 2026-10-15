package com.udcf.modules.faulttolerance.dto;

import com.udcf.modules.replication.ConsistencyModel;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/modules/faulttolerance/stream/start}. The upper rate limit comes from
 * configuration and is checked by the module.
 *
 * <p>No dedicated test: a record; validated in FaultToleranceControllerTest.</p>
 */
public record StreamCommand(@NotNull ConsistencyModel mode, @NotNull @Min(1) Integer ratePerSecond) {
}
