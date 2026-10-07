package com.udcf.modules.clocksync.berkeley;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Complete summary of one Berkeley clock synchronization round.
 *
 * <p><b>R7 Honesty:</b> Tagged with {@code simulated: true} because physical clocks across
 * all nodes share one machine hardware clock.</p>
 *
 * @param daemonNodeId            the time daemon node that coordinated this round
 * @param outlierThresholdMillis  outlier rejection threshold applied
 * @param averageOffsetMillis     the fault-tolerant average offset computed from non-outlier nodes
 * @param spreadBeforeMillis      max offset minus min offset among participating nodes before sync
 * @param spreadAfterMillis       max offset minus min offset among synchronized nodes after sync
 * @param adjustments             individual node adjustments
 * @param participatingNodes      node IDs that participated and were included in the average
 * @param outlierNodes            node IDs whose readings exceeded the threshold and were excluded
 * @param simulated               always true (R7 compliance)
 */
public record BerkeleyRoundResult(
        int daemonNodeId,
        long outlierThresholdMillis,
        long averageOffsetMillis,
        long spreadBeforeMillis,
        long spreadAfterMillis,
        List<NodeAdjustment> adjustments,
        List<Integer> participatingNodes,
        List<Integer> outlierNodes,
        boolean simulated
) {

    public BerkeleyRoundResult {
        if (daemonNodeId < 1) {
            throw new IllegalArgumentException("daemonNodeId must be >= 1, was " + daemonNodeId);
        }
        if (outlierThresholdMillis < 0) {
            throw new IllegalArgumentException("outlierThresholdMillis must be >= 0, was " + outlierThresholdMillis);
        }
        adjustments = adjustments == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(adjustments);
        participatingNodes = participatingNodes == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(participatingNodes);
        outlierNodes = outlierNodes == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(outlierNodes);
        simulated = true;
    }
}
