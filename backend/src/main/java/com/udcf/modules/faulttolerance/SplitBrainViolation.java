package com.udcf.modules.faulttolerance;

import java.util.List;
import java.util.Objects;

/**
 * One way a snapshot breaks the single-primary rule ({@link SplitBrainChecker}). Data only: the
 * module writes the message.
 *
 * <p>Covered by FaultToleranceRecordsTest (validation) and SplitBrainCheckerTest.</p>
 *
 * @param kind         which rule is broken
 * @param nodeIds      the primaries involved, ascending: two or more for a duplicate, one for a stale primary
 * @param epoch        the epoch they act at
 * @param highestEpoch the highest epoch any live node knows
 */
public record SplitBrainViolation(Kind kind, List<Integer> nodeIds, long epoch, long highestEpoch) {

    /** The two violations the checker reports; either one fails the check. */
    public enum Kind {

        /** Two or more live primaries at the same epoch: a real split brain. */
        DUPLICATE_PRIMARY,

        /**
         * A live primary below the highest epoch. Backups that know the higher epoch refuse its
         * writes, but it should never be live as primary: a recovering node stays a non-primary
         * until its role query returns, so seeing this is a bug.
         */
        STALE_PRIMARY
    }

    public SplitBrainViolation {
        Objects.requireNonNull(kind, "kind must not be null");
        nodeIds = List.copyOf(Objects.requireNonNull(nodeIds, "nodeIds must not be null"));
        EpochRules.requireEpoch("epoch", epoch);
        EpochRules.requireEpoch("highestEpoch", highestEpoch);
        if (epoch > highestEpoch) {
            throw new IllegalArgumentException("epoch " + epoch + " is above the highest epoch " + highestEpoch);
        }
        if (kind == Kind.DUPLICATE_PRIMARY && nodeIds.size() < 2) {
            throw new IllegalArgumentException("a duplicate needs at least two primaries, was " + nodeIds);
        }
        if (kind == Kind.STALE_PRIMARY && (nodeIds.size() != 1 || epoch == highestEpoch)) {
            throw new IllegalArgumentException("a stale primary is one node below the highest epoch, was "
                    + nodeIds + " at " + epoch + " of " + highestEpoch);
        }
    }
}
