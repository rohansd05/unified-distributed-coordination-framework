package com.udcf.modules.faulttolerance;

import java.util.List;
import java.util.Objects;

/**
 * Experiment 8 at one moment ({@link FailoverCluster#snapshot()}), for E8c's overview.
 *
 * <p>Covered by FailoverRecordsTest (validation) and FailoverClusterTest.</p>
 *
 * @param started             whether {@link FailoverCluster#start()} ran since the last reset
 * @param phase               the failover phase
 * @param primaryId           the primary the state machine knows; null before the first appointment
 * @param primaryEpoch        its epoch; null exactly when {@code primaryId} is
 * @param highestEpoch        the highest epoch observed or issued by the epoch authority
 * @param nodes               every node, by id
 * @param latestRun           the latest failover run; null if none
 * @param runs                the kept runs, oldest first
 * @param rejectedEventCount  state-machine events rejected as out of order (each one is also logged)
 * @param acknowledgedUpdates keys in the acknowledged ledger
 */
public record FailoverSnapshot(boolean started, FailoverPhase phase, Integer primaryId, Long primaryEpoch,
                               long highestEpoch, List<NodeFailoverState> nodes, FailoverRun latestRun,
                               List<FailoverRun> runs, long rejectedEventCount, int acknowledgedUpdates) {

    public FailoverSnapshot {
        Objects.requireNonNull(phase, "phase must not be null");
        if ((primaryId == null) != (primaryEpoch == null)) {
            throw new IllegalArgumentException("primaryEpoch must be given exactly when primaryId is");
        }
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes must not be null"));
        runs = List.copyOf(Objects.requireNonNull(runs, "runs must not be null"));
        if (rejectedEventCount < 0 || acknowledgedUpdates < 0) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
    }
}
