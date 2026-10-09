package com.udcf.core.cluster;

/**
 * A cluster-wide role a node holds (docs/HANDOFF.md 6.7: {@code GET /api/cluster} lists each
 * node's roles).
 *
 * <p>Only the election module assigns roles, through {@link Cluster#assignLeader(Integer)}.
 * The replication primary and backups join here when Phase 9A wires link L1.</p>
 *
 * <p>No dedicated test: a plain enum with no behaviour.</p>
 */
public enum NodeRole {
    /** The coordinator every live node agreed on in the last election (Experiment 4). */
    LEADER
}
