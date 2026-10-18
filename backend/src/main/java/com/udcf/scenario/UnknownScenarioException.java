package com.udcf.scenario;

/**
 * No scenario has this id. HTTP 404.
 *
 * <p>No dedicated test: a constructor and an accessor; tested through ScenarioControllerTest.</p>
 */
public class UnknownScenarioException extends RuntimeException {

    private final String scenarioId;

    public UnknownScenarioException(String scenarioId) {
        super("No scenario with id '" + scenarioId + "'");
        this.scenarioId = scenarioId;
    }

    public String scenarioId() {
        return scenarioId;
    }
}
