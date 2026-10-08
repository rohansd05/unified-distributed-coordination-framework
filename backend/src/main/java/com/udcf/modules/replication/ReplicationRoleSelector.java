package com.udcf.modules.replication;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Module-local role selector for the replication primary and its backups.
 *
 * <p>Until cluster-wide election roles are wired in Phase 9A, the primary is the lowest-id
 * live node, and every other node in the cluster, crashed or not, is a backup: a crashed
 * backup is still part of the replica set, which is how it diverges and later converges.</p>
 */
public final class ReplicationRoleSelector {

    // TODO(L1): replaced by the shared role provider in Phase 9A
    private ReplicationRoleSelector() {
    }

    /**
     * @param liveNodeIds the live node ids, not empty
     * @return the lowest live id
     */
    public static int selectPrimary(Collection<Integer> liveNodeIds) {
        // TODO(L1): replaced by the shared role provider in Phase 9A
        Objects.requireNonNull(liveNodeIds, "liveNodeIds must not be null");
        if (liveNodeIds.isEmpty()) {
            throw new IllegalArgumentException("liveNodeIds must not be empty");
        }
        return Collections.min(liveNodeIds);
    }

    /**
     * @param primaryId  the primary, which must be one of {@code allNodeIds}
     * @param allNodeIds every node in the cluster, live or crashed
     * @return every node except the primary, ascending, without duplicates
     */
    public static List<Integer> backupsOf(int primaryId, Collection<Integer> allNodeIds) {
        Objects.requireNonNull(allNodeIds, "allNodeIds must not be null");
        TreeSet<Integer> backups = new TreeSet<>(allNodeIds);
        if (!backups.remove(primaryId)) {
            throw new IllegalArgumentException("primary " + primaryId + " is not one of the nodes " + backups);
        }
        return List.copyOf(backups);
    }
}
