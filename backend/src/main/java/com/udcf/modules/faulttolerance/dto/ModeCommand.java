package com.udcf.modules.faulttolerance.dto;

import com.udcf.modules.replication.ConsistencyModel;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PUT /api/modules/faulttolerance/mode}: the model of the next updates.
 *
 * <p>No dedicated test: a record; validated in FaultToleranceControllerTest.</p>
 */
public record ModeCommand(@NotNull ConsistencyModel mode) {
}
