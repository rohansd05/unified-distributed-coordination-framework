package com.udcf.modules.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Key-by-key comparison of replicas, used for the final consistency check and to highlight
 * stale values per replica.
 *
 * <p>Ported from legacy-demos/exp05-replication ({@code ReplicationDemo.printConsistencyCheck}),
 * which compared every backup's dump with the primary's. Differences: it says how each key
 * differs (missing, stale, ahead or a same-version conflict) instead of only "differs", and it
 * is reusable rather than printed.</p>
 */
public final class ConsistencyCheck {

    private ConsistencyCheck() {
    }

    /**
     * Compares every replica with the reference replica.
     *
     * <p>Unreachable replicas are simply left out of {@code replicas} by the caller.</p>
     *
     * @param referenceNodeId the replica treated as correct (normally the primary); must be a key of {@code replicas}
     * @param replicas        each replica's store contents, by node id
     */
    public static ConsistencyReport compare(int referenceNodeId, Map<Integer, Map<String, DataItem>> replicas) {
        Objects.requireNonNull(replicas, "replicas must not be null");
        Map<String, DataItem> reference = replicas.get(referenceNodeId);
        if (reference == null) {
            throw new IllegalArgumentException("reference node " + referenceNodeId + " is not among the replicas "
                    + new TreeSet<>(replicas.keySet()));
        }
        List<Integer> compared = new ArrayList<>();
        List<ReplicaDivergence> divergences = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, DataItem>> entry : new TreeMap<>(replicas).entrySet()) {
            int nodeId = entry.getKey();
            if (nodeId == referenceNodeId) {
                continue;
            }
            Map<String, DataItem> replica = Objects.requireNonNull(entry.getValue(),
                    "replica " + nodeId + " must not be null");
            compared.add(nodeId);
            TreeSet<String> keys = new TreeSet<>(reference.keySet());
            keys.addAll(replica.keySet());
            for (String key : keys) {
                DataItem ref = reference.get(key);
                DataItem rep = replica.get(key);
                DivergenceKind kind = classify(ref, rep);
                if (kind != null) {
                    divergences.add(new ReplicaDivergence(nodeId, key, ref, rep, kind));
                }
            }
        }
        return new ConsistencyReport(referenceNodeId, compared, divergences);
    }

    /** Null when the two agree exactly. */
    private static DivergenceKind classify(DataItem reference, DataItem replica) {
        if (Objects.equals(reference, replica)) {
            return null;
        }
        if (replica == null) {
            return DivergenceKind.MISSING;
        }
        if (reference == null) {
            return DivergenceKind.AHEAD;
        }
        if (replica.sameVersionAs(reference)) {
            return DivergenceKind.CONFLICT;
        }
        return reference.isNewerThan(replica) ? DivergenceKind.STALE : DivergenceKind.AHEAD;
    }
}
