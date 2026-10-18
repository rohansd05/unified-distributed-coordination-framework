package com.udcf.scenario;

import com.udcf.scenario.dto.RunAcceptedDto;
import com.udcf.scenario.dto.RunDto;
import com.udcf.scenario.dto.ScenarioDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Guided scenarios across modules (Phase 9A.6), under {@code /api/scenarios}.
 *
 * <p>Errors are ProblemDetail bodies: from the shared GlobalExceptionHandler 400 for a non-numeric
 * run id; from here 404 "Unknown scenario" ({@code scenarioId}), 404 "Unknown scenario run"
 * ({@code runId}) and 409 "Scenario busy" ({@code reason: SCENARIO_BUSY}, {@code runId} of the run in
 * progress).</p>
 */
@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    static final String SCENARIO_BUSY = "SCENARIO_BUSY";

    private final ScenarioEngine engine;

    public ScenarioController(ScenarioEngine engine) {
        this.engine = engine;
    }

    /** Every scenario with its steps' narration, in order. */
    @GetMapping
    public List<ScenarioDto> scenarios() {
        return engine.scenarios();
    }

    /** 202: the run starts in the background (clean reset first); 404 unknown id; 409 while a run is in progress. */
    @PostMapping("/{scenarioId}/runs")
    public ResponseEntity<RunAcceptedDto> start(@PathVariable String scenarioId) {
        return ResponseEntity.accepted().body(engine.start(scenarioId));
    }

    /** 200 the most recently started run (running or finished); 204 if none is kept. */
    @GetMapping("/runs/latest")
    public ResponseEntity<RunDto> latest() {
        return engine.latestRun().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 200 one kept run; 404 for an unknown id. */
    @GetMapping("/runs/{runId}")
    public RunDto run(@PathVariable long runId) {
        return engine.run(runId);
    }

    @ExceptionHandler(UnknownScenarioException.class)
    public ProblemDetail unknownScenario(UnknownScenarioException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Unknown scenario");
        problem.setProperty("scenarioId", e.scenarioId());
        return problem;
    }

    @ExceptionHandler(UnknownScenarioRunException.class)
    public ProblemDetail unknownRun(UnknownScenarioRunException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Unknown scenario run");
        problem.setProperty("runId", e.runId());
        return problem;
    }

    @ExceptionHandler(ScenarioBusyException.class)
    public ProblemDetail busy(ScenarioBusyException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Scenario busy");
        problem.setProperty("reason", SCENARIO_BUSY);
        problem.setProperty("runId", e.runId());
        return problem;
    }
}
