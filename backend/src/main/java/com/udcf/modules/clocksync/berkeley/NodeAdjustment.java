package com.udcf.modules.clocksync.berkeley;

/**
 * Adjustment computed for one node in a Berkeley synchronization round.
 *
 * @param nodeId             the node identifier
 * @param beforeOffsetMillis the offset before adjustment
 * @param adjustmentMillis   the adjustment to add to the node's clock (0 if outlier)
 * @param afterOffsetMillis  the resulting offset after adjustment
 * @param outlier            true if this node's reading was excluded as an outlier
 */
public record NodeAdjustment(
        int nodeId,
        long beforeOffsetMillis,
        long adjustmentMillis,
        long afterOffsetMillis,
        boolean outlier
) {

    public NodeAdjustment {
        if (nodeId < 1) {
            throw new IllegalArgumentException("nodeId must be >= 1, was " + nodeId);
        }
    }
}
