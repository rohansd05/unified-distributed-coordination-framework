package com.udcf.demo;

import java.time.LocalTime;

/**
 * One recorded clock event, used to build the causally ordered timeline and to verify
 * the causal invariant at the end of the demonstration.
 *
 * @param nodeId       the node on which the event occurred
 * @param type         LOCAL, SEND or RECV
 * @param lamportTime  the Lamport timestamp resulting from the event
 * @param peerId       the other node involved, or 0 for a purely local event
 * @param causedByTime for a RECV, the timestamp carried by the incoming message;
 *                     -1 otherwise. This is what lets the verification step prove that
 *                     every receive is ordered strictly after the send that caused it.
 * @param description  human-readable summary
 * @param wallTime     real clock time, for human reference only — never used for ordering
 */
public record DemoEvent(
        int nodeId,
        String type,
        long lamportTime,
        int peerId,
        long causedByTime,
        String description,
        LocalTime wallTime
) {
}
