package com.udcf.modules.clocksync.berkeley;

import java.time.Instant;
import java.util.Objects;

/**
 * Snapshot of a node's physical clock state and simulated drift offset.
 *
 * <p><b>R7 Honesty:</b> All UDCF nodes execute inside one JVM on a single machine sharing
 * one hardware clock. The physical clock drift is simulated and labelled with {@code simulated: true}.</p>
 *
 * @param nodeId            the node identifier (&gt;= 1)
 * @param offsetMillis      the current simulated offset relative to reference wall clock
 * @param driftRateMsPerSec the drift rate in milliseconds per second
 * @param simulatedTime     the simulated physical time (reference wall time + offset)
 * @param simulated         always true (R7 compliance)
 */
public record NodeDriftSnapshot(
        int nodeId,
        long offsetMillis,
        double driftRateMsPerSec,
        Instant simulatedTime,
        boolean simulated
) {

    public NodeDriftSnapshot {
        if (nodeId < 1) {
            throw new IllegalArgumentException("nodeId must be >= 1, was " + nodeId);
        }
        Objects.requireNonNull(simulatedTime, "simulatedTime must not be null");
        // Ensure simulated is true per R7
        simulated = true;
    }

    public static NodeDriftSnapshot of(int nodeId, long offsetMillis, double driftRateMsPerSec, Instant simulatedTime) {
        return new NodeDriftSnapshot(nodeId, offsetMillis, driftRateMsPerSec, simulatedTime, true);
    }
}
