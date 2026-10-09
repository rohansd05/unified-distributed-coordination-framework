package com.udcf.modules.faulttolerance;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses the Experiment 8 primary until Phase 9A: the lowest-id live node that is ready to
 * serve (docs/tracks/README.md section 6, the same rule as Experiment 5's selector).
 *
 * <p>A node is a candidate only if it is up, its faulttolerance service runs and it finished its
 * rejoin ({@link RejoinState#READY}): a node that has not resynchronised may lack confirmed
 * updates. Its choice is fed to {@link EpochAuthority#onLeaderElected}, which decides the epoch.</p>
 *
 * <p>Pure and stateless. Covered by FailoverRoleSelectorTest.</p>
 */
public final class FailoverRoleSelector {

    /** One node as the selector sees it. */
    public record Candidate(int nodeId, boolean eligible) {
    }

    private FailoverRoleSelector() {
    }

    /**
     * @param excluded nodes that must not be chosen (the failed primary, a node that just failed to take over)
     * @return the lowest eligible id not excluded, or empty if there is none
     */
    public static Optional<Integer> select(Collection<Candidate> candidates, Set<Integer> excluded) {
        // TODO(L1): replaced by the shared role provider in Phase 9A
        Objects.requireNonNull(candidates, "candidates must not be null");
        Objects.requireNonNull(excluded, "excluded must not be null");
        return candidates.stream()
                .filter(Candidate::eligible)
                .map(Candidate::nodeId)
                .filter(id -> !excluded.contains(id))
                .min(Integer::compare);
    }
}
