package com.udcf.modules.faulttolerance;

/**
 * What a node does in Experiment 8's primary-backup scheme.
 *
 * <p>Module-local: the cluster-wide PRIMARY and BACKUP roles join {@code core.cluster.NodeRole}
 * when Phase 9A wires link L1, and only the election module assigns cluster roles. Unlike the
 * legacy demo there is no FAILED role: liveness is the node's status, carried separately (for
 * example in {@link NodeRoleSnapshot#alive()}), and a crashed node keeps the role it believed in.</p>
 *
 * <p>No dedicated test: an enum without behaviour (R6).</p>
 */
public enum FailoverRole {

    /** Accepts client updates and replicates them to the backups. */
    PRIMARY,

    /** Applies updates pushed by the primary. */
    BACKUP
}
