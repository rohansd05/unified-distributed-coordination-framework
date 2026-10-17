package com.udcf.modules.clocksync;

/**
 * Thrown when a Berkeley round needs a time daemon and Experiment 4 could not elect a leader in
 * time. Mapped to HTTP 409 with {@code reason: NO_LEADER} by {@link ClockSyncController}; never
 * answered with another node.
 *
 * <p>No dedicated test: a constructor only; ClockSyncControllerTest checks the 409 body.</p>
 */
public class NoLeaderException extends RuntimeException {

    public NoLeaderException() {
        super("No leader could be elected, so there is no time daemon: no live node won an election in time. "
                + "Recover a node or reset the cluster, then try again.");
    }
}
