package com.udcf.modules.mpi;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One collective to run. The root is an explicit input (E9c chooses it; link L1 later makes it
 * the leader); the live nodes are a snapshot the caller takes at the start.
 *
 * @param collectiveId       identifies the collective on every node (at least 1)
 * @param operation          broadcast, scatter or gather
 * @param rootNodeId         the root
 * @param participantNodeIds the requested nodes (ids at least 1, no duplicates; checked by {@link RankAssignment})
 * @param liveNodeIds        the nodes that are up at the start
 * @param count              vector length n: broadcast and scatter send a vector of n values; gather assembles n values
 * @param seed               the seed of the generated data ({@link PayloadGenerator})
 */
public record CollectiveRequest(long collectiveId, CollectiveOperation operation, int rootNodeId,
                                List<Integer> participantNodeIds, Set<Integer> liveNodeIds, int count, long seed) {

    public CollectiveRequest {
        if (collectiveId < 1) {
            throw new IllegalArgumentException("collectiveId must be >= 1, was " + collectiveId);
        }
        Objects.requireNonNull(operation, "operation must not be null");
        if (rootNodeId < 1) {
            throw new IllegalArgumentException("rootNodeId must be >= 1, was " + rootNodeId);
        }
        participantNodeIds = List.copyOf(Objects.requireNonNull(participantNodeIds, "participantNodeIds must not be null"));
        liveNodeIds = Set.copyOf(Objects.requireNonNull(liveNodeIds, "liveNodeIds must not be null"));
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1, was " + count);
        }
    }
}
