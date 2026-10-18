package com.udcf.scenario.dto;

/**
 * A step is PENDING until it starts; RUNNING; then PASSED or FAILED. After a FAILED step every
 * remaining step is SKIPPED.
 *
 * <p>No dedicated test: a plain enum; the transitions are tested in ScenarioEngineTest.</p>
 */
public enum StepState {
    PENDING,
    RUNNING,
    PASSED,
    FAILED,
    SKIPPED
}
