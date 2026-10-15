package com.udcf.modules.faulttolerance;

import com.udcf.modules.faulttolerance.dto.CrashDto;
import com.udcf.modules.faulttolerance.dto.FaultToleranceOverviewDto;
import com.udcf.modules.faulttolerance.dto.FaultToleranceStatusDto;
import com.udcf.modules.faulttolerance.dto.ModeCommand;
import com.udcf.modules.faulttolerance.dto.RecoverDto;
import com.udcf.modules.faulttolerance.dto.RunDto;
import com.udcf.modules.faulttolerance.dto.StartDto;
import com.udcf.modules.faulttolerance.dto.StreamCommand;
import com.udcf.modules.faulttolerance.dto.StreamDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Experiment 8's REST API. Thin on purpose: validates, delegates to {@link FaultToleranceModule},
 * maps to HTTP. GETs start nothing, record nothing and publish nothing.
 *
 * <p>Errors are ProblemDetail bodies. From the shared GlobalExceptionHandler: 400 for invalid
 * input (field messages under {@code errors}) and 409 while the module is busy. From this
 * controller, in the same shape: 404 for no run yet or an unknown run ({@code runId}), and 409
 * when the action is not possible now ({@code moduleId}, {@code reason}).</p>
 */
@RestController
@RequestMapping("/api/modules/faulttolerance")
public class FaultToleranceController {

    private final FaultToleranceModule module;

    public FaultToleranceController(FaultToleranceModule module) {
        this.module = module;
    }

    /** Status, nodes, stream, latest run, settings, glossary and notes. */
    @GetMapping
    public FaultToleranceOverviewDto overview() {
        return module.overview();
    }

    /** The live part only: roles and epochs per node, the primary, the stream, the checks. */
    @GetMapping("/status")
    public FaultToleranceStatusDto status() {
        return module.currentState();
    }

    /** 202 when it started (the first primary is appointed asynchronously); 200 when it already ran. */
    @PostMapping("/start")
    public ResponseEntity<StartDto> start() {
        StartDto result = module.start();
        return FaultToleranceModule.STARTED.equals(result.outcome())
                ? ResponseEntity.accepted().body(result) : ResponseEntity.ok(result);
    }

    /** Starts the update stream (and Experiment 8 if needed); 202. */
    @PostMapping("/stream/start")
    public ResponseEntity<StreamDto> startStream(@Valid @RequestBody StreamCommand command) {
        return ResponseEntity.accepted().body(module.startStream(command));
    }

    @PostMapping("/stream/stop")
    public StreamDto stopStream() {
        return module.stopStream();
    }

    /** The model of the next updates, whether a stream runs or not. */
    @PutMapping("/mode")
    public StreamDto mode(@Valid @RequestBody ModeCommand command) {
        return module.setMode(command);
    }

    /** Crashes the serving primary; 202, the failover follows on its own. */
    @PostMapping("/crash-primary")
    public ResponseEntity<CrashDto> crashPrimary() {
        return ResponseEntity.accepted().body(module.crashPrimary());
    }

    /** Recovers the latest run's old primary; 202, it rejoins on its own. */
    @PostMapping("/recover")
    public ResponseEntity<RecoverDto> recover() {
        return ResponseEntity.accepted().body(module.recoverOldPrimary());
    }

    /** The kept runs, newest first; each carries its milestones (the timeline). */
    @GetMapping("/runs")
    public List<RunDto> runs() {
        return module.runs();
    }

    /** The latest run; 404 when none since the last reset. */
    @GetMapping("/runs/latest")
    public RunDto latest() {
        return module.latestRun();
    }

    /** One kept run; 404 for an unknown id. */
    @GetMapping("/runs/{runId}")
    public RunDto run(@PathVariable long runId) {
        return module.run(runId);
    }

    @ExceptionHandler(UnknownFailoverRunException.class)
    public ProblemDetail unknownRun(UnknownFailoverRunException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Unknown run");
        problem.setProperty("runId", e.runId());
        return problem;
    }

    @ExceptionHandler(FaultToleranceConflictException.class)
    public ProblemDetail conflict(FaultToleranceConflictException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Not possible now");
        problem.setProperty("moduleId", FaultToleranceModule.ID);
        problem.setProperty("reason", e.reason().name());
        return problem;
    }
}
