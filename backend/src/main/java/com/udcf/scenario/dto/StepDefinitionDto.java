package com.udcf.scenario.dto;

/**
 * A step as defined, before any run: its 1-based index and narration.
 *
 * <p>No dedicated test: a record; the JSON shape is tested in ScenarioControllerTest.</p>
 */
public record StepDefinitionDto(int index, String narration) {
}
