package com.udcf.modules.faulttolerance;

import com.udcf.modules.replication.DataStore;

/**
 * One promotion issued by {@link EpochAuthority}: which node becomes primary, at which epoch.
 *
 * <p>Covered by FaultToleranceRecordsTest (validation) and EpochAuthorityTest.</p>
 *
 * @param nodeId            the node to promote, at least 1
 * @param epoch             its new epoch, always above {@link DataStore#INITIAL_EPOCH}
 * @param previousPrimaryId the primary it replaces, or null for the first appointment
 * @param observedAtNanos   when the election result behind it was observed (the module's shared
 *                          monotonic nano clock)
 */
public record Promotion(int nodeId, long epoch, Integer previousPrimaryId, long observedAtNanos) {

    public Promotion {
        EpochRules.requireNodeId("nodeId", nodeId);
        if (epoch <= DataStore.INITIAL_EPOCH) {
            throw new IllegalArgumentException("a promotion epoch must be above the initial epoch "
                    + DataStore.INITIAL_EPOCH + ", was " + epoch);
        }
        if (previousPrimaryId != null) {
            EpochRules.requireNodeId("previousPrimaryId", previousPrimaryId);
            if (previousPrimaryId == nodeId) {
                throw new IllegalArgumentException("node " + nodeId + " cannot replace itself");
            }
        }
    }
}
