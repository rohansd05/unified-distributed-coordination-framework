package com.udcf.modules.faulttolerance;

/**
 * No kept failover run has this id, or (with a null id) no run is kept at all. Mapped to HTTP 404.
 *
 * <p>No dedicated test: a constructor and an accessor; FaultToleranceControllerTest checks the 404 bodies.</p>
 */
public class UnknownFailoverRunException extends RuntimeException {

    private final Long runId;

    public UnknownFailoverRunException(Long runId) {
        super(runId == null ? "No failover has happened since the last reset" : "No kept failover run has id " + runId);
        this.runId = runId;
    }

    /** The id asked for; null when the latest run was asked for and there is none. */
    public Long runId() {
        return runId;
    }
}
