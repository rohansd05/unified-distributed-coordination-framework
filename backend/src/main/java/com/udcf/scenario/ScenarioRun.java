package com.udcf.scenario;

import com.udcf.scenario.dto.EndStateDto;
import com.udcf.scenario.dto.RunDto;
import com.udcf.scenario.dto.RunState;
import com.udcf.scenario.dto.StepDto;
import com.udcf.scenario.dto.StepState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The live state of one run. Written by the runner thread, read by HTTP threads; every method is
 * synchronized and {@link #toDto()} returns a copy.
 */
final class ScenarioRun {

    private final long runId;
    private final String scenarioId;
    private final String title;
    private final Instant startedAt;
    private final List<MutableStep> steps = new ArrayList<>();

    private RunState state = RunState.RUNNING;
    private Instant finishedAt;
    private String verdict;
    private EndStateDto endState;

    ScenarioRun(long runId, Scenario scenario, Instant startedAt) {
        this.runId = runId;
        this.scenarioId = scenario.id();
        this.title = scenario.title();
        this.startedAt = startedAt;
        for (ScenarioStep step : scenario.steps()) {
            steps.add(new MutableStep(step.narration()));
        }
    }

    long runId() {
        return runId;
    }

    String scenarioId() {
        return scenarioId;
    }

    String title() {
        return title;
    }

    int stepCount() {
        return steps.size();
    }

    synchronized boolean isRunning() {
        return state == RunState.RUNNING;
    }

    synchronized void stepStarted(int index, Instant at) {
        MutableStep step = step(index);
        step.state = StepState.RUNNING;
        step.startedAt = at;
    }

    synchronized void stepEnded(int index, StepState state, long durationMs, Map<String, Object> facts,
                                String message) {
        MutableStep step = step(index);
        step.state = state;
        step.durationMs = durationMs;
        step.facts = new LinkedHashMap<>(facts);
        step.message = message;
    }

    synchronized void skip(int index, String message) {
        MutableStep step = step(index);
        step.state = StepState.SKIPPED;
        step.message = message;
    }

    /** Ends the run; a step still PENDING or RUNNING (only after an engine error) becomes SKIPPED. */
    synchronized void finish(RunState outcome, String verdict, EndStateDto endState, Instant at) {
        for (MutableStep step : steps) {
            if (step.state == StepState.PENDING || step.state == StepState.RUNNING) {
                step.state = StepState.SKIPPED;
                if (step.message == null) {
                    step.message = "Not run: the scenario ended early.";
                }
            }
        }
        this.state = outcome;
        this.verdict = verdict;
        this.endState = endState;
        this.finishedAt = at;
    }

    synchronized RunDto toDto() {
        List<StepDto> dtos = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            MutableStep step = steps.get(i);
            dtos.add(new StepDto(i + 1, step.narration, step.state, step.startedAt, step.durationMs,
                    new LinkedHashMap<>(step.facts), step.message));
        }
        return new RunDto(runId, scenarioId, state, startedAt, finishedAt, verdict, endState, List.copyOf(dtos));
    }

    private MutableStep step(int index) {
        return steps.get(index - 1);
    }

    private static final class MutableStep {
        private final String narration;
        private StepState state = StepState.PENDING;
        private Instant startedAt;
        private Long durationMs;
        private Map<String, Object> facts = Map.of();
        private String message;

        private MutableStep(String narration) {
            this.narration = narration;
        }
    }
}
