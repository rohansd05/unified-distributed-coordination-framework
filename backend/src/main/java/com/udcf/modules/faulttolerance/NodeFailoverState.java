package com.udcf.modules.faulttolerance;

import java.util.Objects;

/**
 * One node as Experiment 8 sees it, for {@link FailoverSnapshot}.
 *
 * <p>Covered by FailoverRecordsTest (validation) and FailoverClusterTest.</p>
 *
 * @param nodeId            the node
 * @param nodeStatus        the cluster status (UP or CRASHED)
 * @param serviceRunning    whether its faulttolerance service runs
 * @param actingPrimary     whether its replication service acts as primary right now
 * @param serving           whether Experiment 8 sends client updates to it (promoted and announced)
 * @param epoch             the replication epoch it acts at if primary, else its store epoch;
 *                          null if it has no replication service
 * @param believedPrimaryId the primary named by its own term record; null if unknown or the node is down
 * @param rejoin            its rejoin state; null if it has no faulttolerance service
 */
public record NodeFailoverState(int nodeId, String nodeStatus, boolean serviceRunning, boolean actingPrimary,
                                boolean serving, Long epoch, Integer believedPrimaryId, RejoinState rejoin) {

    public NodeFailoverState {
        EpochRules.requireNodeId("nodeId", nodeId);
        Objects.requireNonNull(nodeStatus, "nodeStatus must not be null");
        if (serving && !actingPrimary) {
            throw new IllegalArgumentException("node " + nodeId + " cannot serve without acting as primary");
        }
    }
}
