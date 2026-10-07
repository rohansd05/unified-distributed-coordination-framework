package com.udcf.modules.clocksync.berkeley;

/**
 * A clock offset reported by one node during a Berkeley polling phase.
 *
 * @param nodeId       the reporting node (&gt;= 1)
 * @param offsetMillis the node's simulated offset from the true reference clock (or daemon)
 */
public record NodeClockReading(
        int nodeId,
        long offsetMillis
) {

    public NodeClockReading {
        if (nodeId < 1) {
            throw new IllegalArgumentException("nodeId must be >= 1, was " + nodeId);
        }
    }
}
