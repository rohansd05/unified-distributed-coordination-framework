package com.udcf.modules.faulttolerance;

import java.util.Objects;

/**
 * One failover, as recorded by {@link FailoverStateMachine}: the instants it passed through,
 * on the module's shared monotonic nano clock. An instant that never happened is null.
 *
 * <p>Covered by FaultToleranceRecordsTest (validation) and FailoverStateMachineTest.</p>
 *
 * @param runId                      1, 2, 3, ... since the last reset
 * @param oldPrimaryId               the primary that failed
 * @param oldEpoch                   the epoch it was primary at
 * @param crashAtNanos               when it crashed, or null if the run began with a suspicion
 * @param crashSource                how the crash instant was stamped; null exactly when there is none
 * @param detectedAtNanos            the first suspicion of the old primary by any live node
 * @param detectedByNodeId           the node whose detector suspected it first
 * @param electedAtNanos             when the election result behind the promotion was observed
 * @param newPrimaryId               the node chosen to replace it
 * @param newEpoch                   its epoch; null exactly when {@code newPrimaryId} is
 * @param promotedAtNanos            when the new primary started acting as primary
 * @param restoredAtNanos            the new primary's first accepted write ("service restored")
 * @param oldPrimaryRecoveredAtNanos when the old primary came back
 * @param demotedAtNanos             when the old primary demoted itself on seeing the higher epoch
 * @param resyncedAtNanos            when the old primary finished resynchronising
 * @param outcome                    how the run ended so far
 */
public record FailoverRun(long runId, int oldPrimaryId, long oldEpoch, Long crashAtNanos, InstantSource crashSource,
                          Long detectedAtNanos, Integer detectedByNodeId, Long electedAtNanos, Integer newPrimaryId,
                          Long newEpoch, Long promotedAtNanos, Long restoredAtNanos, Long oldPrimaryRecoveredAtNanos,
                          Long demotedAtNanos, Long resyncedAtNanos, Outcome outcome) {

    /** How a run ended so far. */
    public enum Outcome {

        /** Not finished: the service is not restored yet. */
        IN_PROGRESS,

        /** The new primary accepted a write. Recovery of the old primary may still follow. */
        RESTORED,

        /** The old primary answered again before anyone was promoted, and kept its role. */
        PRIMARY_RETURNED,

        /** Another failure began before this run restored the service; this run stays unfinished. */
        INTERRUPTED
    }

    public FailoverRun {
        if (runId < 1) {
            throw new IllegalArgumentException("runId must be >= 1, was " + runId);
        }
        EpochRules.requireNodeId("oldPrimaryId", oldPrimaryId);
        EpochRules.requireEpoch("oldEpoch", oldEpoch);
        if ((crashAtNanos == null) != (crashSource == null)) {
            throw new IllegalArgumentException("crashSource must be given exactly when crashAtNanos is");
        }
        if ((newPrimaryId == null) != (newEpoch == null)) {
            throw new IllegalArgumentException("newEpoch must be given exactly when newPrimaryId is");
        }
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    /** The intervals of this run; each is null until both of its instants are recorded. */
    public FailoverMeasurements measurements() {
        return FailoverMeasurements.of(this);
    }
}
