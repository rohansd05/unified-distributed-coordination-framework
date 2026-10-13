package com.udcf.modules.matrix;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One parallel matrix run to make (step E10b). The root is an explicit input (E10c chooses it); the live
 * nodes are a snapshot the caller takes at the start.
 *
 * <p><b>Collective ids.</b> The run's scatter and broadcast use ids {@value #COLLECTIVE_ID_BASE} + 2 x runId
 * and that + 1, so they never meet Experiment 9's own collectives, whose ids count from 1 per reset. They
 * are stored on the nodes like any collective, so the MPI page lists them among each node's kept ids.</p>
 *
 * @param runId              identifies the run (1 to {@link #MAX_RUN_ID}); also the key of the kept blocks of C
 * @param n                  the matrix size (checked against the limits by the runner, which refuses as data)
 * @param seed               the user's seed; A and B come from {@code MatrixGenerator.seedA/seedB(seed)}
 * @param rootNodeId         the root
 * @param participantNodeIds the requested nodes (ids at least 1, no duplicates; checked by RankAssignment)
 * @param liveNodeIds        the nodes that are up at the start
 */
public record MatrixRunRequest(long runId, int n, long seed, int rootNodeId, List<Integer> participantNodeIds,
                               Set<Integer> liveNodeIds) {

    /** Matrix collective ids start above this, far from Experiment 9's ids (which count from 1). */
    public static final long COLLECTIVE_ID_BASE = 1_000_000_000L;

    /** The largest run id whose collective ids still fit a long. */
    public static final long MAX_RUN_ID = (Long.MAX_VALUE - COLLECTIVE_ID_BASE - 1) / 2;

    public MatrixRunRequest {
        if (runId < 1 || runId > MAX_RUN_ID) {
            throw new IllegalArgumentException("runId must be in 1.." + MAX_RUN_ID + ", was " + runId);
        }
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1, was " + n);
        }
        if (rootNodeId < 1) {
            throw new IllegalArgumentException("rootNodeId must be >= 1, was " + rootNodeId);
        }
        participantNodeIds = List.copyOf(Objects.requireNonNull(participantNodeIds, "participantNodeIds must not be null"));
        liveNodeIds = Set.copyOf(Objects.requireNonNull(liveNodeIds, "liveNodeIds must not be null"));
    }

    /** The collective id of the scatter of A. */
    public long scatterCollectiveId() {
        return COLLECTIVE_ID_BASE + 2 * runId;
    }

    /** The collective id of the broadcast of B. */
    public long broadcastCollectiveId() {
        return scatterCollectiveId() + 1;
    }
}
