package com.udcf.scenario;

/**
 * A scenario run is in progress; only one runs at a time. HTTP 409 {@code SCENARIO_BUSY} with its runId.
 *
 * <p>No dedicated test: a constructor and an accessor; tested through ScenarioEngineTest and
 * ScenarioControllerTest.</p>
 */
public class ScenarioBusyException extends RuntimeException {

    private final long runId;

    public ScenarioBusyException(long runId, String title) {
        super("The scenario \"" + title + "\" is running (run " + runId + "). Wait until it finishes.");
        this.runId = runId;
    }

    public long runId() {
        return runId;
    }
}
