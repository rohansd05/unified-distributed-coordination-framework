package com.udcf.scenario.dto;

/**
 * {@code POST /api/scenarios/{id}/runs} (202): the run that was started.
 *
 * <p>No dedicated test: a record; the JSON shape is tested in ScenarioControllerTest.</p>
 */
public record RunAcceptedDto(long runId, String scenarioId) {
}
