package com.udcf.modules.faulttolerance;

/**
 * Where a node's faulttolerance service is in rejoining the cluster after a recovery.
 *
 * <p>No dedicated test: an enum. FaultToleranceNodeServiceTest and FailoverClusterTest check
 * the transitions.</p>
 */
public enum RejoinState {

    /** Never crashed since the service started, or rejoined and resynchronised: may be promoted. */
    READY,

    /** Crashed or recovering: its role query and resynchronisation have not finished. Never primary. */
    REJOINING,

    /** No peer answered its role query: it stays non-primary and never invents a role. */
    WAITING_FOR_ANSWER
}
