package com.udcf.modules.faulttolerance.dto;

/**
 * The measured intervals of one failover run in milliseconds; null until both instants are recorded.
 *
 * <p>No dedicated test: a record; mapped and tested in FaultToleranceDtoMapperTest.</p>
 *
 * @param detectionMillis       crash to the first suspicion by any live node
 * @param failoverMillis        detection to the new primary promoted
 * @param serviceRestoredMillis promotion to the first accepted update
 * @param outageMillis          crash to the first accepted update, measured directly
 * @param recoveryMillis        old primary back to demoted and resynchronised
 */
public record MeasurementsDto(Double detectionMillis, Double failoverMillis, Double serviceRestoredMillis,
                              Double outageMillis, Double recoveryMillis) {
}
