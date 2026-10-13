package com.udcf.modules.mpi;

/**
 * How a collective ended, judged on its remote transfers (the root's own part is local and the
 * skipped nodes are not participants).
 *
 * <p>No dedicated test: an enum without behaviour (R6); the rules are in CollectiveExecutor and
 * proved by CollectiveExecutorTest.</p>
 */
public enum CollectiveOutcome {
    /** Every remote transfer was delivered, or none was needed (the root is the only rank). */
    COMPLETE,
    /** At least one transfer was delivered and at least one failed or was not attempted. */
    PARTIAL,
    /** At least one remote transfer was needed and none was delivered. */
    FAILED,
    /** Refused before anything was sent (see {@link Refusal}). */
    REFUSED
}
