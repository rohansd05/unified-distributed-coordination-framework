package com.udcf.modules.mpi;

/**
 * Which way a transfer's data went.
 *
 * <p>No dedicated test: an enum without behaviour (R6).</p>
 */
public enum TransferDirection {
    /** From the root to the rank (broadcast, scatter). */
    TO_RANK,
    /** From the rank to the root (gather). */
    FROM_RANK
}
