package com.udcf.scenario.dto;

import java.util.List;

/**
 * {@code GET /api/scenarios}: one scenario and its steps' narration (static, no numbers).
 *
 * <p>No dedicated test: a record; the JSON shape is tested in ScenarioControllerTest.</p>
 */
public record ScenarioDto(String id, String title, String summary, List<String> modules,
                          List<StepDefinitionDto> steps) {
}
