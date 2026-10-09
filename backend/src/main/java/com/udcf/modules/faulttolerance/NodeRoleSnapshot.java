package com.udcf.modules.faulttolerance;

import java.util.Objects;

/**
 * One node's role and epoch at one moment, for {@link SplitBrainChecker}.
 *
 * <p>Covered by FaultToleranceRecordsTest (validation) and SplitBrainCheckerTest.</p>
 *
 * @param nodeId the node, at least 1
 * @param alive  false if the node is crashed (a crashed node keeps the role it believed in)
 * @param role   its role
 * @param epoch  for a primary, the epoch it acts as primary at; for a backup, its store epoch
 */
public record NodeRoleSnapshot(int nodeId, boolean alive, FailoverRole role, long epoch) {

    public NodeRoleSnapshot {
        EpochRules.requireNodeId("nodeId", nodeId);
        Objects.requireNonNull(role, "role must not be null");
        EpochRules.requireEpoch("epoch", epoch);
    }
}
