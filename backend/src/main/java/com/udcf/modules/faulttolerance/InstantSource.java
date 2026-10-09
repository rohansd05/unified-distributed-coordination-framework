package com.udcf.modules.faulttolerance;

/**
 * Where a crash instant came from, so the page can say how exact it is.
 *
 * <p>No dedicated test: an enum without behaviour (R6).</p>
 */
public enum InstantSource {

    /** Stamped by the module's own crash action immediately before {@code Cluster.crash}: exact. */
    ACTION,

    /**
     * Stamped when the {@code NODE_CRASHED} event was delivered (a crash started elsewhere, for
     * example on the Cluster page). The event bus delivers asynchronously, so the instant is
     * slightly late and a detection time measured from it slightly short.
     */
    OBSERVED
}
