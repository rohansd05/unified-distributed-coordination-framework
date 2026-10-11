package com.udcf.modules.rmi.dto;

/**
 * A node's Experiment 1 state, read live from the cluster and the node's RMI service.
 *
 * <p>No dedicated test: an enum without behaviour (R6).</p>
 */
public enum RmiNodeState {

    /** The node is up, but its RMI registry and endpoint are not exported (never started, or stopped by a reset). */
    NOT_STARTED,

    /** The node is up and its NodeEndpoint is exported in its own registry. */
    EXPORTED,

    /** The node is crashed: nothing listens on its RMI port. */
    CRASHED
}
