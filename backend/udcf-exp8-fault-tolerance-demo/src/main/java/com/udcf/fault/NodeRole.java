package com.udcf.fault;

/**
 * What a node currently believes its own job is.
 *
 * <p>The role is not fixed at startup. A BACKUP promotes itself to PRIMARY when it
 * detects the primary has died, and an old PRIMARY that comes back after a crash
 * demotes itself to BACKUP once it learns a newer primary exists.</p>
 */
public enum NodeRole {

    /** Accepts system updates from clients and replicates them outward. */
    PRIMARY,

    /** Applies updates sent by the primary and monitors it with heartbeats. */
    BACKUP,

    /** The process is down. Nothing is served and nothing is answered. */
    FAILED
}
