package com.udcf.modules.replication;

/**
 * Thrown when an Experiment 5 action needs the replication primary and Experiment 4 could not
 * elect a leader in time. Mapped to HTTP 409 with {@code reason: NO_LEADER} by
 * {@link ReplicationController}; never answered with another node.
 *
 * <p>No dedicated test: a constructor only; ReplicationControllerTest checks the 409 body.</p>
 */
public class NoLeaderException extends RuntimeException {

    public NoLeaderException() {
        super("No leader could be elected, so Experiment 5 has no primary: no live node won an election in time. "
                + "Recover a node or reset the cluster, then try again.");
    }
}
