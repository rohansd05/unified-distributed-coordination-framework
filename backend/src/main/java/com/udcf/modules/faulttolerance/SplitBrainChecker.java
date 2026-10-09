package com.udcf.modules.faulttolerance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Checks a snapshot of (node, role, epoch) for split brain: the guarantee Experiment 8
 * demonstrates is that two primaries never coexist.
 *
 * <p>Only live nodes count: a crashed node cannot accept writes, whatever it believes. The check
 * is strict; it fails on either violation ({@link SplitBrainViolation.Kind}):</p>
 * <ul>
 *   <li>two or more live primaries at the same epoch ({@code DUPLICATE_PRIMARY});</li>
 *   <li>a live primary below the highest epoch any live node knows ({@code STALE_PRIMARY}).</li>
 * </ul>
 * <p>No live primary (during an outage) is not a violation.</p>
 *
 * <p>Thread safety: stateless.</p>
 */
public final class SplitBrainChecker {

    private SplitBrainChecker() {
    }

    public static SplitBrainReport check(List<NodeRoleSnapshot> snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Set<Integer> seen = new HashSet<>();
        for (NodeRoleSnapshot node : snapshot) {
            Objects.requireNonNull(node, "snapshot must not contain null");
            if (!seen.add(node.nodeId())) {
                throw new IllegalArgumentException("node " + node.nodeId() + " appears twice");
            }
        }
        List<NodeRoleSnapshot> live = snapshot.stream().filter(NodeRoleSnapshot::alive).toList();
        if (live.isEmpty()) {
            return new SplitBrainReport(true, null, List.of(), List.of());
        }
        long highest = live.stream().mapToLong(NodeRoleSnapshot::epoch).max().getAsLong();
        List<NodeRoleSnapshot> primaries = live.stream()
                .filter(n -> n.role() == FailoverRole.PRIMARY)
                .sorted((a, b) -> Integer.compare(a.nodeId(), b.nodeId()))
                .toList();

        Map<Long, List<Integer>> byEpoch = new TreeMap<>();
        for (NodeRoleSnapshot primary : primaries) {
            byEpoch.computeIfAbsent(primary.epoch(), e -> new ArrayList<>()).add(primary.nodeId());
        }
        List<SplitBrainViolation> violations = new ArrayList<>();
        byEpoch.forEach((epoch, ids) -> {
            if (ids.size() > 1) {
                violations.add(new SplitBrainViolation(SplitBrainViolation.Kind.DUPLICATE_PRIMARY, ids, epoch, highest));
            }
        });
        for (NodeRoleSnapshot primary : primaries) {
            if (primary.epoch() < highest) {
                violations.add(new SplitBrainViolation(SplitBrainViolation.Kind.STALE_PRIMARY,
                        List.of(primary.nodeId()), primary.epoch(), highest));
            }
        }
        return new SplitBrainReport(violations.isEmpty(), highest,
                primaries.stream().map(NodeRoleSnapshot::nodeId).toList(), violations);
    }
}
