package com.udcf.scenario;

/**
 * A step's expectation was not met (or its deadline passed). Its message is the step's message,
 * built from what was observed.
 *
 * <p>No dedicated test: a constructor only; the engine's handling is tested in ScenarioEngineTest.</p>
 */
public class StepFailedException extends RuntimeException {

    public StepFailedException(String message) {
        super(message);
    }
}
