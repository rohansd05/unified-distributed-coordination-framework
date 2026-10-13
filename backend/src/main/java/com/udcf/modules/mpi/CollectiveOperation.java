package com.udcf.modules.mpi;

/**
 * The collectives Experiment 9 builds (docs/HANDOFF.md 7). Reduce and allgather are not built;
 * see the E9a section of docs/tracks/track-c-jai.md for how they would be added.
 *
 * <p>No dedicated test: an enum without behaviour (R6).</p>
 */
public enum CollectiveOperation {
    /** The root sends the whole vector to every other participant. */
    BROADCAST,
    /** The root sends block i of the vector to rank i. */
    SCATTER,
    /** Every participant contributes block i's length of its own data; the root assembles them in rank order. */
    GATHER
}
