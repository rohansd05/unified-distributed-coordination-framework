package com.udcf.scenario.dto;

/**
 * A run is RUNNING until it ends PASSED (every step passed) or FAILED (a step failed, or the
 * cluster could not be reset first).
 *
 * <p>No dedicated test: a plain enum; the transitions are tested in ScenarioEngineTest.</p>
 */
public enum RunState {
    RUNNING,
    PASSED,
    FAILED
}
