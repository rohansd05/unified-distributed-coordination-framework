package com.udcf.modules.mpi;

import com.udcf.modules.mpi.dto.CollectiveCommand;
import com.udcf.modules.mpi.dto.CollectiveDto;
import com.udcf.modules.mpi.dto.MpiOverviewDto;
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
 * Experiment 9's REST API. Thin on purpose: delegates to {@link MpiModule}, maps to HTTP. A
 * collective answers 200 whatever its outcome (COMPLETE, PARTIAL, FAILED, REFUSED): a failed
 * transfer is data, not an HTTP error.
 *
 * <p>Errors are ProblemDetail bodies. From the shared GlobalExceptionHandler: 404 for an unknown
 * root node, 409 "Node down" for a crashed root, 409 "Node state conflict" when every node
 * is crashed, 409 "Module busy". From this controller: 409 "No leader" ({@code reason: NO_LEADER}) when
 * no root was requested and no leader could be elected. From this controller, in the shared shape: 400 "Invalid request
 * parameters" with every offending field under {@code errors}, 404 "Unknown collective". An
 * unreadable body or a non-numeric id gets Spring's default 400, without {@code errors}.</p>
 */
@RestController
@RequestMapping("/api/modules/mpi")
public class MpiController {

    static final String INVALID_PARAMETERS_TITLE = "Invalid request parameters";

    private final MpiModule module;

    public MpiController(MpiModule module) {
        this.module = module;
    }

    /** Status, operations, limits, participants, default root, texts and the latest collective. */
    @GetMapping
    public MpiOverviewDto overview() {
        return module.overview();
    }

    /** Starts RMI on every node that is up; idempotent. */
    @PostMapping("/start")
    public MpiOverviewDto start() {
        return module.start();
    }

    /** Runs one collective; 200 for every outcome. */
    @PostMapping("/collectives")
    public CollectiveDto run(@RequestBody CollectiveCommand command) {
        return module.run(command);
    }

    /** The kept collectives, newest first. */
    @GetMapping("/collectives")
    public List<CollectiveDto> collectives() {
        return module.history();
    }

    /** The newest kept collective; 404 when none. */
    @GetMapping("/collectives/latest")
    public CollectiveDto latest() {
        return module.latest();
    }

    /** One kept collective; 404 for an unknown id. */
    @GetMapping("/collectives/{id}")
    public CollectiveDto collective(@PathVariable long id) {
        return module.collective(id);
    }

    @ExceptionHandler(UnknownCollectiveException.class)
    public ProblemDetail unknownCollective(UnknownCollectiveException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Unknown collective");
        problem.setProperty("collectiveId", e.collectiveId());
        return problem;
    }

    @ExceptionHandler(InvalidCallException.class)
    public ProblemDetail invalidCollective(InvalidCallException e) {
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
        problem.setProperty("moduleId", MpiModule.ID);
        problem.setProperty("reason", "NO_LEADER");
        return problem;
    }
}
