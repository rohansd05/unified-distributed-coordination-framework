package com.udcf.modules.faulttolerance.dto;

import com.udcf.core.module.ModuleStatus;

import java.util.List;

/**
 * Experiment 8 now. Reading it runs the no-two-primaries check and the live data-loss check, but
 * records nothing and publishes nothing.
 *
 * <p>No dedicated test: a record; mapped and tested in FaultToleranceDtoMapperTest.</p>
 *
 * @param actionInProgress   the guarded action running now; null when not BUSY
 * @param started            whether Experiment 8 runs since the last reset
 * @param phase              STEADY, SUSPECTED, PROMOTING or RESTORED
 * @param currentPrimaryId   the primary serving client updates; null if none (before start, during a failover)
 * @param primaryEpoch       its epoch; null exactly when {@code currentPrimaryId} is
 * @param highestEpoch       the highest epoch observed or issued since the last reset
 * @param dataLossNow        acknowledged updates the serving primary lacks now; null while no primary serves
 * @param rejectedEventCount state-machine events rejected as out of order (each one is logged)
 */
public record FaultToleranceStatusDto(ModuleStatus status, String actionInProgress, boolean started, String phase,
                                      Integer currentPrimaryId, Long primaryEpoch, long highestEpoch,
                                      List<NodeStateDto> nodes, StreamDto stream, SplitBrainDto splitBrain,
                                      DataLossDto dataLossNow, int acknowledgedUpdates, long rejectedEventCount) {
}
