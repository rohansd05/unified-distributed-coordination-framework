package com.udcf.modules.mpi;

import com.udcf.modules.mpi.dto.RootSource;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * Chooses a collective's root (module-local, as docs/tracks/README.md section 6 asks): the
 * requested node; otherwise the elected leader if it is live (read-only from
 * {@code Cluster.leaderId()}, link L1); otherwise the lowest live node id. Pure.
 */
final class MpiRoleSelector {

    /** The chosen root and the rule that chose it. */
    record Choice(int nodeId, RootSource source) {
    }

    private MpiRoleSelector() {
    }

    /**
     * @param requested the root the request named, or null
     * @param leader    the cluster's leader, if any
     * @param live      the node ids that are up (the snapshot the collective runs with)
     * @return empty only when nothing was requested and no node is live
     */
    // TODO(L1): replaced by the shared role provider when Phase 13 wires the leader as the MPI root
    static Optional<Choice> select(Integer requested, Optional<Integer> leader, Collection<Integer> live) {
        Objects.requireNonNull(leader, "leader must not be null");
        Objects.requireNonNull(live, "live must not be null");
        if (requested != null) {
            return Optional.of(new Choice(requested, RootSource.REQUESTED));
        }
        if (leader.isPresent() && live.contains(leader.get())) {
            return Optional.of(new Choice(leader.get(), RootSource.LEADER));
        }
        return live.stream().min(Comparator.naturalOrder()).map(id -> new Choice(id, RootSource.LOWEST_LIVE_ID));
    }
}
