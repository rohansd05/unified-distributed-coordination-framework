package com.udcf.modules.faulttolerance;

import java.util.Objects;

/**
 * What happened when the update client sent one update to one node.
 *
 * <p>Covered by FaultToleranceRecordsTest (validation) and UpdateRetryPolicyTest.</p>
 *
 * @param kind        what happened
 * @param nodeId      the node the update was sent to
 * @param primaryHint for {@link Kind#NOT_PRIMARY} only: the primary that node named, or null if it named none
 */
public record AttemptOutcome(Kind kind, int nodeId, Integer primaryHint) {

    /** The three answers a node can give, including silence. */
    public enum Kind {

        /** The node is the primary and confirmed the update. */
        ACCEPTED,

        /** The node is not the primary (or was superseded); it may name the one it believes in. */
        NOT_PRIMARY,

        /** The node is down or did not answer. */
        UNREACHABLE
    }

    public AttemptOutcome {
        Objects.requireNonNull(kind, "kind must not be null");
        EpochRules.requireNodeId("nodeId", nodeId);
        if (primaryHint != null) {
            if (kind != Kind.NOT_PRIMARY) {
                throw new IllegalArgumentException("only a not-primary answer names a primary");
            }
            EpochRules.requireNodeId("primaryHint", primaryHint);
        }
    }

    public static AttemptOutcome accepted(int nodeId) {
        return new AttemptOutcome(Kind.ACCEPTED, nodeId, null);
    }

    public static AttemptOutcome notPrimary(int nodeId, Integer primaryHint) {
        return new AttemptOutcome(Kind.NOT_PRIMARY, nodeId, primaryHint);
    }

    public static AttemptOutcome unreachable(int nodeId) {
        return new AttemptOutcome(Kind.UNREACHABLE, nodeId, null);
    }
}
