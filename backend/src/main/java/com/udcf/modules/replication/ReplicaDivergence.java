package com.udcf.modules.replication;

import java.util.Objects;

/**
 * One key on which one replica differs from the reference replica.
 *
 * <p>No dedicated test: a record; its construction is covered by {@code ConsistencyCheckTest} (R6).</p>
 *
 * @param nodeId    the replica that differs
 * @param key       the key
 * @param reference the reference replica's item, or null if it lacks the key
 * @param replica   this replica's item, or null if it lacks the key
 * @param kind      how they differ
 */
public record ReplicaDivergence(int nodeId, String key, DataItem reference, DataItem replica,
                                DivergenceKind kind) {

    public ReplicaDivergence {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
    }
}
