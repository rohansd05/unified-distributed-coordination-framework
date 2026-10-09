package com.udcf.modules.faulttolerance;

import java.util.List;
import java.util.Objects;

/**
 * The result of one split-brain check ({@link SplitBrainChecker#check}).
 *
 * <p>Covered by FaultToleranceRecordsTest (validation) and SplitBrainCheckerTest.</p>
 *
 * @param passed        true exactly when there are no violations
 * @param highestEpoch  the highest epoch any live node knows, or null if no node is live
 * @param livePrimaries the live nodes acting as primary, ascending (empty during an outage, which is not a violation)
 * @param violations    duplicates first (by epoch), then stale primaries (by node id)
 */
public record SplitBrainReport(boolean passed, Long highestEpoch, List<Integer> livePrimaries,
                               List<SplitBrainViolation> violations) {

    public SplitBrainReport {
        livePrimaries = List.copyOf(Objects.requireNonNull(livePrimaries, "livePrimaries must not be null"));
        violations = List.copyOf(Objects.requireNonNull(violations, "violations must not be null"));
        if (passed != violations.isEmpty()) {
            throw new IllegalArgumentException("passed must be true exactly when there are no violations");
        }
        if (highestEpoch == null && !livePrimaries.isEmpty()) {
            throw new IllegalArgumentException("live primaries need a highest epoch");
        }
    }
}
