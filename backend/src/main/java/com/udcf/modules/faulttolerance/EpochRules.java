package com.udcf.modules.faulttolerance;

import com.udcf.modules.replication.DataStore;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The epoch rules of Experiment 8, as pure functions.
 *
 * <p>An epoch (Raft's "term") rises by one on every promotion, and every update carries the
 * epoch of the primary that accepted it. These rules decide <b>roles</b>: whether a node refuses,
 * accepts or adopts an epoch, whether a primary must demote, and what a recovering node does
 * after its role query. <b>Writes</b> are fenced only by the shared replication store
 * ({@link DataStore#apply(com.udcf.modules.replication.DataItem, long)}); {@link #judge} mirrors
 * that fence exactly, and EpochRulesTest checks the two agree.</p>
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li>An incoming epoch below the known one is refused ({@link EpochVerdict#REFUSE_STALE}).</li>
 *   <li>An equal epoch is the same term and is accepted ({@link EpochVerdict#ACCEPT}). This is
 *       safe only because {@link EpochAuthority} never issues one epoch twice, so two primaries
 *       can never share an epoch.</li>
 *   <li>A higher epoch is adopted ({@link EpochVerdict#ADOPT_HIGHER}); a primary on the lower
 *       epoch demotes ({@link #mustDemote}).</li>
 *   <li>Epochs never decrease ({@link #afterObserving}).</li>
 * </ul>
 *
 * <p>Ported from legacy-demos/exp08-fault-tolerance ({@code FaultTolerantNode.adoptEpoch},
 * {@code rejoinCluster}, the {@code REPLICATE} epoch check). Every epoch must be at least
 * {@link DataStore#INITIAL_EPOCH}.</p>
 *
 * <p>Thread safety: stateless.</p>
 */
public final class EpochRules {

    private EpochRules() {
    }

    /** Compares an incoming epoch with the highest epoch the node knows. */
    public static EpochVerdict judge(long knownEpoch, long incomingEpoch) {
        requireEpoch("knownEpoch", knownEpoch);
        requireEpoch("incomingEpoch", incomingEpoch);
        if (incomingEpoch < knownEpoch) {
            return EpochVerdict.REFUSE_STALE;
        }
        return incomingEpoch == knownEpoch ? EpochVerdict.ACCEPT : EpochVerdict.ADOPT_HIGHER;
    }

    /** The highest epoch a node knows after seeing {@code observedEpoch}: never lower than before. */
    public static long afterObserving(long knownEpoch, long observedEpoch) {
        requireEpoch("knownEpoch", knownEpoch);
        requireEpoch("observedEpoch", observedEpoch);
        return Math.max(knownEpoch, observedEpoch);
    }

    /**
     * True if a node in {@code role}, acting at {@code primaryEpoch}, must demote on seeing
     * {@code observedEpoch}: only a primary, and only for a strictly higher epoch.
     */
    public static boolean mustDemote(FailoverRole role, long primaryEpoch, long observedEpoch) {
        Objects.requireNonNull(role, "role must not be null");
        requireEpoch("primaryEpoch", primaryEpoch);
        requireEpoch("observedEpoch", observedEpoch);
        return role == FailoverRole.PRIMARY && observedEpoch > primaryEpoch;
    }

    /**
     * The primary named by the answers at the highest epoch among them: a node that reports
     * itself primary there, else the primary those answers believe in. Ties go to the lowest id
     * (two primaries at one epoch would be a split brain, which {@link SplitBrainChecker} reports).
     *
     * @return the primary, or empty if there are no answers or none at the highest epoch names one
     */
    public static Optional<Integer> currentPrimary(List<RoleReport> replies) {
        requireDistinct(replies);
        if (replies.isEmpty()) {
            return Optional.empty();
        }
        long highest = replies.stream().mapToLong(RoleReport::epoch).max().getAsLong();
        Optional<Integer> selfReported = replies.stream()
                .filter(r -> r.epoch() == highest && r.role() == FailoverRole.PRIMARY)
                .map(RoleReport::nodeId)
                .min(Integer::compare);
        if (selfReported.isPresent()) {
            return selfReported;
        }
        return replies.stream()
                .filter(r -> r.epoch() == highest && r.believedPrimaryId() != null)
                .map(RoleReport::believedPrimaryId)
                .min(Integer::compare);
    }

    /**
     * What a recovering node does with the answers to its role query (the answer to "who is in
     * charge now?").
     *
     * <ul>
     *   <li>No answers: {@link RejoinDecision.Action#NO_ANSWER}; the node must not write.</li>
     *   <li>A peer knows a higher epoch: a former primary demotes
     *       ({@link RejoinDecision.Action#DEMOTE_AND_RESYNC}), a backup adopts
     *       ({@link RejoinDecision.Action#ADOPT_AND_RESYNC}); either way it takes the highest
     *       epoch and resynchronises from {@link #currentPrimary} (null if no answer names one).</li>
     *   <li>Otherwise {@link RejoinDecision.Action#STAY}: a former primary is still primary; a
     *       backup follows the primary named at its own epoch.</li>
     * </ul>
     *
     * @param selfId   the recovering node, which must not be among the answers
     * @param ownRole  the role the node held before it crashed
     * @param ownEpoch the highest epoch the node knows
     * @param replies  the answers received (unreachable peers simply do not answer)
     */
    public static RejoinDecision resolveRejoin(int selfId, FailoverRole ownRole, long ownEpoch,
                                               List<RoleReport> replies) {
        requireNodeId("selfId", selfId);
        Objects.requireNonNull(ownRole, "ownRole must not be null");
        requireEpoch("ownEpoch", ownEpoch);
        requireDistinct(replies);
        if (replies.stream().anyMatch(r -> r.nodeId() == selfId)) {
            throw new IllegalArgumentException("node " + selfId + " cannot answer its own role query");
        }
        if (replies.isEmpty()) {
            return new RejoinDecision(RejoinDecision.Action.NO_ANSWER, ownEpoch, null);
        }
        long highest = replies.stream().mapToLong(RoleReport::epoch).max().getAsLong();
        if (highest > ownEpoch) {
            RejoinDecision.Action action = ownRole == FailoverRole.PRIMARY
                    ? RejoinDecision.Action.DEMOTE_AND_RESYNC
                    : RejoinDecision.Action.ADOPT_AND_RESYNC;
            return new RejoinDecision(action, highest, currentPrimary(replies).orElse(null));
        }
        if (ownRole == FailoverRole.PRIMARY) {
            return new RejoinDecision(RejoinDecision.Action.STAY, ownEpoch, selfId);
        }
        List<RoleReport> current = replies.stream().filter(r -> r.epoch() == ownEpoch).toList();
        return new RejoinDecision(RejoinDecision.Action.STAY, ownEpoch, currentPrimary(current).orElse(null));
    }

    static void requireEpoch(String name, long epoch) {
        if (epoch < DataStore.INITIAL_EPOCH) {
            throw new IllegalArgumentException(name + " must be >= " + DataStore.INITIAL_EPOCH + ", was " + epoch);
        }
    }

    static void requireNodeId(String name, int nodeId) {
        if (nodeId < 1) {
            throw new IllegalArgumentException(name + " must be >= 1, was " + nodeId);
        }
    }

    private static void requireDistinct(List<RoleReport> replies) {
        Objects.requireNonNull(replies, "replies must not be null");
        Set<Integer> seen = new HashSet<>();
        for (RoleReport reply : replies) {
            Objects.requireNonNull(reply, "replies must not contain null");
            if (!seen.add(reply.nodeId())) {
                throw new IllegalArgumentException("two answers from node " + reply.nodeId());
            }
        }
    }
}
