package com.udcf.scenario;

/**
 * No kept run has this id (never started, or dropped from the history). HTTP 404.
 *
 * <p>No dedicated test: a constructor and an accessor; tested through ScenarioControllerTest.</p>
 */
public class UnknownScenarioRunException extends RuntimeException {

    private final long runId;

    public UnknownScenarioRunException(long runId) {
        super("No scenario run with id " + runId + " is kept");
        this.runId = runId;
    }

    public long runId() {
        return runId;
    }
}
