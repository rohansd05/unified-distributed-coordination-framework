package com.udcf.modules.faulttolerance.dto;

import com.udcf.modules.replication.ConsistencyModel;

import java.time.Instant;

/**
 * The update stream. Counts are for the current or last stream since the last reset.
 *
 * <p>No dedicated test: a record; mapped and tested in FaultToleranceDtoMapperTest.</p>
 *
 * @param state            RUNNING or STOPPED
 * @param mode             the model of the next update
 * @param ratePerSecond    the rate of the current or last stream; null if none since the reset
 * @param inFlightSequence the update being sent now; null if none
 * @param maxUpdates       updates all streams may send between two cluster resets
 * @param remainingUpdates what is left of that budget
 * @param startedAt        null if no stream since the reset
 * @param stoppedAt        null while running or if none
 * @param stopReason       REQUESTED, LIMIT_REACHED or ERROR; null while running or if none
 */
public record StreamDto(String state, ConsistencyModel mode, Integer ratePerSecond, int sent, int acknowledged,
                        int gaveUp, Integer inFlightSequence, int maxUpdates, int remainingUpdates, Instant startedAt,
                        Instant stoppedAt, String stopReason) {
}
