package com.udcf.modules.mpi;

/**
 * What happened to one rank's part of a collective.
 *
 * <p>No dedicated test: an enum without behaviour (R6).</p>
 */
public enum TransferStatus {
    /** The root's own part: it never crosses the network, so it has no latency and no size. */
    LOCAL,
    /** Sent and answered by the right node for the right collective and rank. */
    DELIVERED,
    /** Tried and failed (with a stage and a kind), or answered with a reply that did not match the request. */
    FAILED,
    /** Never tried: the collective's deadline had passed. Different from FAILED: nothing was learned about the node. */
    NOT_ATTEMPTED
}
