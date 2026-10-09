package com.udcf.modules.election;

import java.time.Instant;

/**
 * One election round as {@link ElectionRoundTracker} sees it: a snapshot, never updated in place.
 *
 * <p>No dedicated test: a plain record. ElectionRoundTrackerTest checks how it is filled.</p>
 *
 * @param roundId         1, 2, 3, ... since start-up (a reset does not restart the count)
 * @param initiatorNodeId the node the round started from
 * @param startedAt       wall time, for display only
 * @param leaderId        the agreed leader; {@code null} unless ELECTED
 * @param durationMillis  measured from the round's start to agreement; {@code null} unless
 *                        ELECTED (unmeasured, never 0)
 */
public record ElectionRound(
        long roundId,
        ElectionAlgorithm algorithm,
        RoundTrigger trigger,
        int initiatorNodeId,
        Instant startedAt,
        RoundOutcome outcome,
        Integer leaderId,
        Double durationMillis
) {
}
