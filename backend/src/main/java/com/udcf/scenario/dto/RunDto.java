package com.udcf.scenario.dto;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/scenarios/runs/latest} and {@code GET /api/scenarios/runs/{runId}}: one run.
 *
 * <p>No dedicated test: a record; built and tested through ScenarioEngineTest.</p>
 *
 * @param finishedAt null while RUNNING
 * @param verdict    one sentence; null while RUNNING
 * @param endState   null while RUNNING
 */
public record RunDto(long runId, String scenarioId, RunState state, Instant startedAt, Instant finishedAt,
                     String verdict, EndStateDto endState, List<StepDto> steps) {
}
