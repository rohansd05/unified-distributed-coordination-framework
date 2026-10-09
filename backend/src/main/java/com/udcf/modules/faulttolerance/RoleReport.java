package com.udcf.modules.faulttolerance;

import java.util.Objects;

/**
 * One node's answer to a role query: what it does, the highest epoch it knows, and whom it
 * believes to be primary (the legacy {@code ROLE_REPLY role;epoch;primaryId}).
 *
 * <p>Covered by FaultToleranceRecordsTest (validation) and EpochRulesTest.</p>
 *
 * @param nodeId            the answering node, at least 1
 * @param role              its role
 * @param epoch             the highest epoch it knows, at least {@code DataStore.INITIAL_EPOCH}
 * @param believedPrimaryId the primary it believes in, or null if it knows none; a primary
 *                          believes in itself, so for a primary this is null or its own id
 */
public record RoleReport(int nodeId, FailoverRole role, long epoch, Integer believedPrimaryId) {

    public RoleReport {
        EpochRules.requireNodeId("nodeId", nodeId);
        Objects.requireNonNull(role, "role must not be null");
        EpochRules.requireEpoch("epoch", epoch);
        if (believedPrimaryId != null) {
            EpochRules.requireNodeId("believedPrimaryId", believedPrimaryId);
            if (role == FailoverRole.PRIMARY && believedPrimaryId != nodeId) {
                throw new IllegalArgumentException("a primary believes in itself: node " + nodeId
                        + " reported primary " + believedPrimaryId);
            }
        }
    }

    /** The primary this answer points at: the node itself if it is primary, else its belief (may be null). */
    public Integer pointsAt() {
        return role == FailoverRole.PRIMARY ? Integer.valueOf(nodeId) : believedPrimaryId;
    }
}
