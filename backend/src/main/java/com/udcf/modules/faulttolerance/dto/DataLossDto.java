package com.udcf.modules.faulttolerance.dto;

import java.util.List;

/**
 * Acknowledged updates the primary lacks. When the loss was not assessed the counts are null,
 * never 0, and {@code notAssessed} says why.
 *
 * <p>No dedicated test: a record; mapped and tested in FaultToleranceDtoMapperTest.</p>
 *
 * @param acknowledged         updates the client was told had succeeded
 * @param lost                 of those, missing or older on the primary; null if not assessed
 * @param lostKeys             their keys, in sequence order
 * @param simulated            true if any acknowledged update was asynchronous with a simulated delay
 * @param simulatedDelayMillis that delay as the replication service reported it; null if none applied
 * @param simulatedReason      why the delay is simulated; null if none applied
 * @param notAssessed          NOTHING_ACKNOWLEDGED or NO_PRIMARY_STORE; null if assessed
 */
public record DataLossDto(int acknowledged, Integer lost, List<String> lostKeys, Integer lostSynchronous,
                          Integer lostAsynchronous, boolean simulated, Long simulatedDelayMillis,
                          String simulatedReason, String notAssessed) {
}
