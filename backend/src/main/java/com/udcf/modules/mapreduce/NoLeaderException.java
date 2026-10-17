package com.udcf.modules.mapreduce;

/**
 * Thrown when a job needs a coordinator and Experiment 4 could not elect a leader in time.
 * Mapped to HTTP 409 with {@code reason: NO_LEADER} by {@link MapReduceController}; never
 * answered with another node.
 *
 * <p>No dedicated test: a constructor only; MapReduceControllerTest checks the 409 body.</p>
 */
public class NoLeaderException extends RuntimeException {

    public NoLeaderException() {
        super("No leader could be elected, so this job has no coordinator: no live node won an election in time. "
                + "Recover a node or reset the cluster, then try again.");
    }
}
