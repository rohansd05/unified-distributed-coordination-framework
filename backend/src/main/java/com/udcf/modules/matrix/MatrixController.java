package com.udcf.modules.matrix;

import com.udcf.modules.matrix.dto.MatrixOverviewDto;
import com.udcf.modules.matrix.dto.MatrixRunCommand;
import com.udcf.modules.matrix.dto.MatrixRunDto;
import com.udcf.modules.matrix.dto.MatrixSeriesDto;
import com.udcf.modules.mpi.NoLeaderException;
import com.udcf.modules.rmi.InvalidCallException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Experiment 10's REST API. Thin on purpose: delegates to {@link MatrixModule}, maps to HTTP. A run answers
 * 200 whatever its outcome (COMPLETE, PARTIAL, FAILED, REFUSED): a failed transfer or compute is data, not an
 * HTTP error. A run is synchronous and can take up to the overview's worstCaseRunMillis.
 *
 * <p>Errors are ProblemDetail bodies. From the shared GlobalExceptionHandler: 404 for an unknown root node,
 * 409 "Node down" for a crashed root, 409 "Node state conflict" when every node is crashed, 409
 * "Module busy". From this controller, in the shared shape: 400 "Invalid request parameters" with every
 * offending field under {@code errors}, 404 "Unknown run", 409 "No leader" ({@code reason: NO_LEADER}) when no
 * root was requested and no leader could be elected. An unreadable body or a non-numeric id gets
 * Spring's default 400, without {@code errors}.</p>
 */
@RestController
@RequestMapping("/api/modules/matrix")
public class MatrixController {

    static final String INVALID_PARAMETERS_TITLE = "Invalid request parameters";

    private final MatrixModule module;

    public MatrixController(MatrixModule module) {
        this.module = module;
    }

    /** Status, limits and budget, participants, default root, texts and the latest run. */
    @GetMapping
    public MatrixOverviewDto overview() {
        return module.overview();
    }

    /** Starts RMI and the request listeners on every node that is up; idempotent. */
    @PostMapping("/start")
    public MatrixOverviewDto start() {
        return module.start();
    }

    /** Runs one size; 200 for every outcome. */
    @PostMapping("/runs")
    public MatrixRunDto run(@RequestBody MatrixRunCommand command) {
        return module.run(command);
    }

    /** The kept runs, newest first. */
    @GetMapping("/runs")
    public List<MatrixRunDto> runs() {
        return module.history();
    }

    /** The newest kept run; 404 when none. */
    @GetMapping("/runs/latest")
    public MatrixRunDto latest() {
        return module.latest();
    }

    /** One kept run; 404 for an unknown id. */
    @GetMapping("/runs/{id}")
    public MatrixRunDto run(@PathVariable long id) {
        return module.run(id);
    }

    /** The speedup series over the configured sizes. */
    @GetMapping("/series")
    public MatrixSeriesDto series() {
        return module.series();
    }

    @ExceptionHandler(UnknownRunException.class)
    public ProblemDetail unknownRun(UnknownRunException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Unknown run");
        problem.setProperty("runId", e.runId());
        return problem;
    }

    @ExceptionHandler(InvalidCallException.class)
    public ProblemDetail invalidRun(InvalidCallException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle(INVALID_PARAMETERS_TITLE);
        problem.setProperty("errors", e.errors());
        return problem;
    }

    /** No leader could be elected, so a run without a requested root has no root (never another node). */
    @ExceptionHandler(NoLeaderException.class)
    public ProblemDetail noLeader(NoLeaderException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("No leader");
        problem.setProperty("moduleId", MatrixModule.ID);
        problem.setProperty("reason", "NO_LEADER");
        return problem;
    }
}
