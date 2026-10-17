package com.udcf.core.cluster;

/**
 * A cluster-wide role a node holds (docs/HANDOFF.md 6.7: {@code GET /api/cluster} lists each
 * node's roles).
 *
 * <p>Only {@link Cluster} grants roles: {@code LEADER} through {@link Cluster#assignLeader(Integer)}
 * (the election module), {@code PRIMARY} and {@code BACKUP} through
 * {@link RoleProvider#publishReplicationRoles} (the replication owner, link L1).</p>
 *
 * <p>No dedicated test: a plain enum with no behaviour.</p>
 */
public enum NodeRole {
    /** The coordinator every live node agreed on in the last election (Experiment 4). */
    LEADER,

    /** The node acting as replication primary (at most one at a time). */
    PRIMARY,

    /** A live node holding a replica the primary pushes to. */
    BACKUP
}
