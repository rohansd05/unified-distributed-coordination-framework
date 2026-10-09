package com.udcf.modules.faulttolerance;

import java.util.Objects;

/**
 * What a recovering node does after its role query
 * ({@link EpochRules#resolveRejoin(int, FailoverRole, long, java.util.List)}).
 *
 * <p>Covered by FaultToleranceRecordsTest (validation) and EpochRulesTest.</p>
 *
 * @param action    what to do
 * @param epoch     the epoch the node holds afterwards, at least {@code DataStore.INITIAL_EPOCH}
 * @param primaryId the primary to follow and resynchronise from, or null if no answer named one
 */
public record RejoinDecision(Action action, long epoch, Integer primaryId) {

    /** The four outcomes of a role query. */
    public enum Action {

        /** No peer knows a higher epoch: the node keeps the role it held before it crashed. */
        STAY,

        /** The node was primary and a peer knows a higher epoch: it demotes, adopts it and resynchronises. */
        DEMOTE_AND_RESYNC,

        /** The node was a backup and a peer knows a higher epoch: it adopts it and resynchronises. */
        ADOPT_AND_RESYNC,

        /**
         * No peer answered. The node cannot know whether it was replaced, so it must not write
         * on its own authority; the caller retries the query later.
         */
        NO_ANSWER
    }

    public RejoinDecision {
        Objects.requireNonNull(action, "action must not be null");
        EpochRules.requireEpoch("epoch", epoch);
        if (primaryId != null) {
            EpochRules.requireNodeId("primaryId", primaryId);
        }
        if (action == Action.NO_ANSWER && primaryId != null) {
            throw new IllegalArgumentException("no answer names no primary");
        }
    }

    /** True if the node must copy the primary's state ({@link Action#DEMOTE_AND_RESYNC} or {@link Action#ADOPT_AND_RESYNC}). */
    public boolean resync() {
        return action == Action.DEMOTE_AND_RESYNC || action == Action.ADOPT_AND_RESYNC;
    }

    /**
     * True only for {@link Action#STAY}: the node's own view is current. A node may write as
     * primary only after this, and only if it was primary before.
     */
    public boolean viewConfirmed() {
        return action == Action.STAY;
    }
}
