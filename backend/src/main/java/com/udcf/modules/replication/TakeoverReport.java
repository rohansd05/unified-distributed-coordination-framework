package com.udcf.modules.replication;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One change of the node {@link ReplicaSet} makes primary: what the new primary pulled from
 * every live peer before taking over (catch-up), and what it then pushed to every live backup
 * (anti-entropy). On the first selection (or after a reset) there is nothing to catch up, so
 * both lists are empty.
 *
 * <p>Covered by ReplicaSetTest.</p>
 *
 * @param previousPrimaryId the node made primary before, or empty on the first selection
 * @param newPrimaryId      the node made primary now, at least 1
 * @param catchUps          one pull per live peer, in node order; copied
 * @param pushes            one anti-entropy run per live backup, in node order; copied
 * @param lamportTime       the new primary's Lamport time of the {@code PRIMARY_SELECTED} event
 */
public record TakeoverReport(Optional<Integer> previousPrimaryId, int newPrimaryId, List<CatchUpReport> catchUps,
                             List<AntiEntropyReport> pushes, long lamportTime) {

    public TakeoverReport {
        Objects.requireNonNull(previousPrimaryId, "previousPrimaryId must not be null");
        if (newPrimaryId < 1) {
            throw new IllegalArgumentException("newPrimaryId must be >= 1, was " + newPrimaryId);
        }
        catchUps = List.copyOf(Objects.requireNonNull(catchUps, "catchUps must not be null"));
        pushes = List.copyOf(Objects.requireNonNull(pushes, "pushes must not be null"));
    }

    /** Items the new primary stored from its catch-ups. */
    public int appliedFromCatchUp() {
        return catchUps.stream().flatMap(c -> c.merged().stream()).mapToInt(AntiEntropyResult::applied).sum();
    }
}
