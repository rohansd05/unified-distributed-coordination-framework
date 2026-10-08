package com.udcf.modules.replication.dto;

/**
 * How one replica's version of a key compares with the reference replica's (the node last made
 * primary), using the same rules as {@link com.udcf.modules.replication.ConsistencyCheck}.
 *
 * <p>No dedicated test: an enum without behaviour (R6).</p>
 */
public enum ReplicaState {

    /** The same version and value as the reference. */
    CURRENT,

    /** Neither the reference nor this replica holds the key. */
    ABSENT,

    /** The reference holds the key, this replica does not. */
    MISSING,

    /** This replica holds an older version than the reference. */
    STALE,

    /** This replica holds a newer version than the reference, or a key the reference lacks. */
    AHEAD,

    /** The same version with a different value: a protocol fault, never resolved. */
    CONFLICT,

    /** This replica could not be read: it is crashed, or its service never started. */
    UNREACHABLE
}
