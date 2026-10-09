package com.udcf.modules.faulttolerance;

import com.udcf.modules.replication.DataStore;

import java.util.Optional;

/**
 * Issues the epoch of every Experiment 8 promotion, exactly once per election result.
 *
 * <p>The new primary is not chosen here: it arrives through {@link #onLeaderElected(int, long)}
 * (no second Bully). The authority only decides whether that result is a new promotion and, if
 * so, its epoch.</p>
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>A promotion epoch is {@code max(last issued, highest observed) + 1}. The highest
 *       observed epoch starts at {@link DataStore#INITIAL_EPOCH}, the epoch of a fresh store, so
 *       the first promotion is {@code INITIAL_EPOCH + 1}. No promotion epoch ever equals the
 *       initial epoch, an epoch already observed, or one already issued; epochs never decrease.
 *       That is what makes "an equal epoch is the same term" ({@link EpochRules#judge}) safe:
 *       two primaries can never share an epoch.</li>
 *   <li>The same result applied twice promotes once: a result naming the current primary
 *       changes nothing.</li>
 *   <li>A stale result changes nothing: one observed before the promotion now in force.</li>
 *   <li>A leader change with no primary failure (a planned handover, for example a recovered
 *       highest node winning Bully again) is still a promotion, epoch + 1. Before Phase 9A
 *       Experiment 8 does not feed such changes in: a recovered old primary demotes and stays a
 *       backup.</li>
 * </ul>
 *
 * <p>Thread safety: every method is synchronized; none waits or calls out while holding the lock.</p>
 */
public class EpochAuthority {

    private long highestObserved = DataStore.INITIAL_EPOCH;
    private long lastIssued;
    private Promotion current;

    /**
     * An election result: {@code nodeId} should be primary, as observed at {@code observedAtNanos}.
     *
     * @param nodeId          the elected node, at least 1
     * @param observedAtNanos when the result was observed, on the module's shared monotonic nano clock
     * @return the new promotion, or empty if the result is stale or names the current primary
     */
    public synchronized Optional<Promotion> onLeaderElected(int nodeId, long observedAtNanos) {
        // TODO(L1): fed by the election module's leader in Phase 9A; until then E8b feeds it from
        // a module-local selector (highest live id, the legacy rule).
        EpochRules.requireNodeId("nodeId", nodeId);
        if (current != null) {
            if (observedAtNanos < current.observedAtNanos()) {
                return Optional.empty();
            }
            if (nodeId == current.nodeId()) {
                return Optional.empty();
            }
        }
        long epoch = Math.max(lastIssued, highestObserved) + 1;
        lastIssued = epoch;
        current = new Promotion(nodeId, epoch, current == null ? null : current.nodeId(), observedAtNanos);
        return Optional.of(current);
    }

    /**
     * Learns an epoch some node knows (a store epoch, a role-query answer), so every later
     * promotion is above it. Never lowers anything.
     *
     * @return {@link #highestEpoch()} after the call
     */
    public synchronized long observeEpoch(long epoch) {
        EpochRules.requireEpoch("epoch", epoch);
        highestObserved = Math.max(highestObserved, epoch);
        return highestEpoch();
    }

    /** The highest epoch observed or issued; the next promotion is one above it. */
    public synchronized long highestEpoch() {
        return Math.max(lastIssued, highestObserved);
    }

    /** The promotion now in force, or empty before the first one. */
    public synchronized Optional<Promotion> current() {
        return Optional.ofNullable(current);
    }

    /** Back to a fresh authority (module reset; the stores' epochs are reset with it). */
    public synchronized void reset() {
        highestObserved = DataStore.INITIAL_EPOCH;
        lastIssued = 0;
        current = null;
    }
}
