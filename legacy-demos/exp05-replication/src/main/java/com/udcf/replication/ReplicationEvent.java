package com.udcf.replication;

import java.time.LocalTime;

/**
 * One recorded replication event, used for the causally ordered log and the summary
 * statistics printed at the end of the demonstration.
 *
 * @param nodeId      node on which the event occurred
 * @param category    short tag: WRITE, REPLICATE, ACK, STALE, FAIL, RESYNC, READ
 * @param lamportTime Lamport timestamp resulting from the event
 * @param peerId      the other node involved, or 0 for a purely local event
 * @param description human-readable summary
 * @param wallTime    real clock time, for human reference only, never used for ordering
 */
public record ReplicationEvent(
        int nodeId,
        String category,
        long lamportTime,
        int peerId,
        String description,
        LocalTime wallTime
) {
}
