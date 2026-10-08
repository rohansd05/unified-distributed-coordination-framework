package com.udcf.modules.replication;

import java.util.List;
import java.util.Objects;

/**
 * Result of a key-by-key comparison of replicas against a reference replica.
 *
 * <p>No dedicated test: a record whose two methods are covered by {@code ConsistencyCheckTest} (R6).</p>
 *
 * @param referenceNodeId the replica the others were compared with (normally the primary)
 * @param comparedNodeIds every other replica compared, ascending
 * @param divergences     every difference found, by node id then key
 */
public record ConsistencyReport(int referenceNodeId, List<Integer> comparedNodeIds,
                                List<ReplicaDivergence> divergences) {

    public ConsistencyReport {
        comparedNodeIds = List.copyOf(Objects.requireNonNull(comparedNodeIds, "comparedNodeIds must not be null"));
        divergences = List.copyOf(Objects.requireNonNull(divergences, "divergences must not be null"));
    }

    /** True if every compared replica matches the reference exactly. */
    public boolean consistent() {
        return divergences.isEmpty();
    }

    /** How many divergences are of {@code kind}. */
    public long count(DivergenceKind kind) {
        Objects.requireNonNull(kind, "kind must not be null");
        return divergences.stream().filter(d -> d.kind() == kind).count();
    }
}
