package com.udcf.modules.replication;

/**
 * How one replica's copy of a key differs from the reference replica's.
 *
 * <p>No dedicated test: an enum without behaviour (R6).</p>
 */
public enum DivergenceKind {

    /** The reference holds the key; the replica does not. */
    MISSING,

    /** The replica holds an older version than the reference. */
    STALE,

    /** The replica holds a newer version than the reference, or a key the reference lacks. */
    AHEAD,

    /**
     * Both hold the same version (epoch, Lamport time, origin) with different values: a
     * protocol fault, reported on its own and never as STALE or AHEAD.
     */
    CONFLICT
}
