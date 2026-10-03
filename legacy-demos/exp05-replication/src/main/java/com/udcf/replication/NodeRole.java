package com.udcf.replication;

/**
 * Role a node plays in the primary-backup replication model.
 *
 * <p>In the deployed UDCF the role is not hard-coded: the node elected coordinator in
 * Experiment 4 becomes the PRIMARY, and the rest become BACKUPs. This demonstration
 * assigns the roles directly so replication can be studied on its own.</p>
 */
public enum NodeRole {

    /** Accepts all client writes and is the single source of truth for ordering. */
    PRIMARY,

    /** Applies updates pushed by the primary; may serve reads. */
    BACKUP
}
