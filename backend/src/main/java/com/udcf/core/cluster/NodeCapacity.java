package com.udcf.core.cluster;

/**
 * Capacity profile of a node, used by Experiment 6 to make load-balancing differences
 * observable. Values are from docs/HANDOFF.md Appendix B (Experiment 6).
 *
 * <p>Tested by NodeCapacityTest, which guards these numbers.</p>
 */
public enum NodeCapacity {
    FAST(4, 1),
    MEDIUM(2, 2),
    SLOW(1, 4);

    private final int threads;
    private final int workMultiplier;

    NodeCapacity(int threads, int workMultiplier) {
        this.threads = threads;
        this.workMultiplier = workMultiplier;
    }

    /** Worker threads this node gets. */
    public int threads() {
        return threads;
    }

    /** How many times the base work unit each request costs on this node. */
    public int workMultiplier() {
        return workMultiplier;
    }
}
