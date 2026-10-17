package com.udcf.core.cluster;

/**
 * Runs an election when the {@link RoleProvider} is asked for a leader and there is none.
 * Implemented by Experiment 4's {@code ElectionModule}; nothing else decides the leader.
 */
public interface LeaderElector {

    /**
     * Starts one election if there is no leader and none is running; returns at once. May
     * start the election service (and its heartbeats) on every live node.
     */
    void requestElection();

    /** How long one requested election may take, derived from the configured election timeouts. */
    long electionBoundMillis();
}
