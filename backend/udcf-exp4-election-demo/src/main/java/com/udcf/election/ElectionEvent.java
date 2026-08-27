package com.udcf.election;

import java.time.LocalTime;

/**
 * One recorded election event, used for the causally ordered timeline and the
 * summary statistics printed at the end of the demonstration.
 *
 * @param nodeId      the node on which the event occurred
 * @param category    short tag such as ELECTION, OK, COORD, DETECT, CRASH
 * @param lamportTime the Lamport timestamp resulting from the event
 * @param peerId      the other node involved, or 0 for a purely local event
 * @param description human-readable summary
 * @param wallTime    real clock time, for human reference only — never used for ordering
 */
public record ElectionEvent(
        int nodeId,
        String category,
        long lamportTime,
        int peerId,
        String description,
        LocalTime wallTime
) {
}
