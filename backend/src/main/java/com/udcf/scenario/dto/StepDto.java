package com.udcf.scenario.dto;

import java.time.Instant;
import java.util.Map;

/**
 * One step of a run.
 *
 * <p>No dedicated test: a record; built and tested through ScenarioEngineTest.</p>
 *
 * @param index      1-based
 * @param startedAt  null while PENDING or SKIPPED
 * @param durationMs null until the step ends (and for a SKIPPED step)
 * @param facts      observed values (node ids, epochs, counts, timings, bus sequences); empty if none
 * @param message    what was observed, or why it failed or was skipped; null while PENDING or RUNNING
 */
public record StepDto(int index, String narration, StepState state, Instant startedAt, Long durationMs,
                      Map<String, Object> facts, String message) {
}
