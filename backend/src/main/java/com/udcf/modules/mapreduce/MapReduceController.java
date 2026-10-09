package com.udcf.modules.mapreduce;

import com.udcf.modules.mapreduce.dto.MapReduceOverviewDto;
import com.udcf.modules.mapreduce.dto.RunCommand;
import com.udcf.modules.mapreduce.dto.RunDto;
import com.udcf.modules.mapreduce.dto.RunSummaryDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Experiment 7's REST API. Thin on purpose: validates, delegates to {@link MapReduceModule},
 * maps to HTTP. A run executes in the background, so {@code POST /runs} answers 202 with the
 * run as RUNNING; {@code GET /runs/{runId}} shows it finish.
 *
 * <p>Errors are ProblemDetail bodies. From the shared GlobalExceptionHandler: 400 for invalid
 * input (field messages under {@code errors}, including every {@code upload.*} problem and a bad
 * {@code crashWorkerId}) and 409 while a run is active. From this controller, in the same
 * shape: 404 for an unknown job or run, 409 when no node is live, and 413 for a request body
 * over the limit (see {@link RunRequestBodyLimit}).</p>
 */
@RestController
@RequestMapping("/api/modules/mapreduce")
public class MapReduceController {

    private final MapReduceModule module;

    public MapReduceController(MapReduceModule module) {
        this.module = module;
    }

    /** Status, jobs, input types, current roles, limits, the latest run and notes. */
    @GetMapping
    public MapReduceOverviewDto overview() {
        return module.overview();
    }

    /** Starts a run in the background; 202 with the run as RUNNING. */
    @PostMapping("/runs")
    public ResponseEntity<RunDto> run(@Valid @RequestBody RunCommand command) {
        return ResponseEntity.accepted().body(module.startRun(command));
    }

    /** The kept runs, newest first. */
    @GetMapping("/runs")
    public List<RunSummaryDto> runs() {
        return module.runs();
    }

    /** The most recent run; 404 when none is kept. */
    @GetMapping("/runs/latest")
    public RunDto latest() {
        return module.latestRun();
    }

    /** One kept run; 404 for an unknown id. */
    @GetMapping("/runs/{runId}")
    public RunDto run(@PathVariable String runId) {
        return module.run(runId);
    }

    @ExceptionHandler(UnknownJobException.class)
    public ProblemDetail unknownJob(UnknownJobException e) {
        return problem(HttpStatus.NOT_FOUND, "Unknown job", e.getMessage(), "jobId", e.jobId());
    }

    @ExceptionHandler(UnknownRunException.class)
    public ProblemDetail unknownRun(UnknownRunException e) {
        return problem(HttpStatus.NOT_FOUND, "Unknown run", e.getMessage(), "runId", e.runId());
    }

    @ExceptionHandler(NoLiveWorkerException.class)
    public ProblemDetail noLiveWorker(NoLiveWorkerException e) {
        return problem(HttpStatus.CONFLICT, "No live worker", e.getMessage(), "moduleId", MapReduceModule.ID);
    }

    @ExceptionHandler(RequestBodyTooLargeException.class)
    public ProblemDetail tooLarge(RequestBodyTooLargeException e) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Request body too large", e.getMessage(),
                "limitBytes", e.limitBytes());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String key, Object value) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty(key, value);
        return problem;
    }
}
