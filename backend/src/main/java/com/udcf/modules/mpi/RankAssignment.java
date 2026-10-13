package com.udcf.modules.mpi;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Who takes part in a collective, and with which rank: the participants that are live at the
 * start, sorted by ascending node id, get ranks 0 to p-1. The root is one of them; its rank need
 * not be 0. A participant that is not live is excluded and listed as skipped, never dropped
 * silently. A root that is not a participant, or not live, refuses the collective.
 *
 * <p>Example: participants 1 to 5, live {1, 2, 4, 5}, root 4: ranks 0 to 3 are nodes 1, 2, 4, 5,
 * the root has rank 2, and node 3 is skipped.</p>
 */
public final class RankAssignment {

    private final List<Integer> nodes;
    private final List<Integer> skipped;
    private final int rootNodeId;
    private final Refusal refusal;

    private RankAssignment(List<Integer> nodes, List<Integer> skipped, int rootNodeId, Refusal refusal) {
        this.nodes = nodes;
        this.skipped = skipped;
        this.rootNodeId = rootNodeId;
        this.refusal = refusal;
    }

    /**
     * Assigns ranks.
     *
     * @param participants the requested node ids (each at least 1, no duplicates, any order)
     * @param live         the node ids that are up at the start
     * @param rootNodeId   the root
     * @throws IllegalArgumentException for a null, duplicate or non-positive id
     */
    public static RankAssignment assign(List<Integer> participants, Set<Integer> live, int rootNodeId) {
        Objects.requireNonNull(participants, "participants must not be null");
        Objects.requireNonNull(live, "live must not be null");
        Set<Integer> seen = new HashSet<>();
        for (Integer id : participants) {
            if (id == null || id < 1) {
                throw new IllegalArgumentException("every participant must be a node id >= 1, was " + id);
            }
            if (!seen.add(id)) {
                throw new IllegalArgumentException("participant " + id + " is listed twice");
            }
        }
        List<Integer> ranked = participants.stream().filter(live::contains).sorted().toList();
        List<Integer> skipped = participants.stream().filter(id -> !live.contains(id)).sorted().toList();
        Refusal refusal = null;
        if (participants.isEmpty()) {
            refusal = new Refusal(RefusalReason.NO_PARTICIPANTS, "The collective names no participant.");
        } else if (!seen.contains(rootNodeId)) {
            refusal = new Refusal(RefusalReason.ROOT_NOT_PARTICIPANT,
                    "The root, node " + rootNodeId + ", is not one of the participants.");
        } else if (!live.contains(rootNodeId)) {
            refusal = new Refusal(RefusalReason.ROOT_DOWN, "The root, node " + rootNodeId + ", is down.");
        }
        return new RankAssignment(refusal == null ? ranked : List.of(), skipped, rootNodeId, refusal);
    }

    /** Why ranks could not be assigned; empty when they were. */
    public Optional<Refusal> refusal() {
        return Optional.ofNullable(refusal);
    }

    /** The node of each rank, rank 0 first; empty when refused. */
    public List<Integer> nodes() {
        return nodes;
    }

    /** The participants that were not live, ascending. */
    public List<Integer> skipped() {
        return skipped;
    }

    /** The number of ranks, p. */
    public int size() {
        return nodes.size();
    }

    /** The root's rank. */
    public int rootRank() {
        if (refusal != null) {
            throw new IllegalStateException("the assignment was refused: " + refusal.message());
        }
        return nodes.indexOf(rootNodeId);
    }
}
