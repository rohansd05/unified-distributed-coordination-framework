package com.udcf.modules.faulttolerance;

/**
 * Where the cluster is in a failover ({@link FailoverStateMachine}).
 *
 * <p>No dedicated test: an enum without behaviour, covered by FailoverStateMachineTest (R6).</p>
 */
public enum FailoverPhase {

    /**
     * A primary serves, as far as the cluster knows. A crash does not change this: nothing
     * announces a crash, so until a failure detector suspects the primary the cluster has not
     * noticed (the crash instant is still recorded in the run).
     */
    STEADY,

    /** A failure detector suspects the primary; the cluster waits for a new primary to be chosen. */
    SUSPECTED,

    /** A new primary was chosen; the service is not restored until it accepts its first write. */
    PROMOTING,

    /** The new primary accepted a write: the service is restored. Behaves like {@link #STEADY}. */
    RESTORED
}
