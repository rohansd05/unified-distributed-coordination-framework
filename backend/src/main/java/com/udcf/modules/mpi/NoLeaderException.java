package com.udcf.modules.mpi;

/**
 * Thrown when a collective (Experiment 9) or a matrix run (Experiment 10) needs the default root
 * and Experiment 4 could not elect a leader in time. Mapped to HTTP 409 with
 * {@code reason: NO_LEADER} by {@link MpiController} and {@code MatrixController}; never answered
 * with another node.
 *
 * <p>No dedicated test: a constructor only; MpiControllerTest and MatrixControllerTest check the
 * 409 body.</p>
 */
public class NoLeaderException extends RuntimeException {

    /** @param what what has no root, for example "a collective" */
    public NoLeaderException(String what) {
        super("No leader could be elected, so " + what + " has no root: no live node won an election in time. "
                + "Recover a node or reset the cluster, then try again, or choose a root.");
    }
}
