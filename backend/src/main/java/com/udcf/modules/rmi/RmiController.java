package com.udcf.modules.rmi;

import com.udcf.modules.rmi.dto.CallCommand;
import com.udcf.modules.rmi.dto.ComparisonDto;
import com.udcf.modules.rmi.dto.HistoryEntryDto;
import com.udcf.modules.rmi.dto.RegistryDto;
import com.udcf.modules.rmi.dto.RmiCallDto;
import com.udcf.modules.rmi.dto.RmiOverviewDto;
import jakarta.validation.Valid;
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
 * Experiment 1's REST API. Thin on purpose: validates, delegates to {@link RmiModule}, maps to
 * HTTP. Calls and comparisons are synchronous and answer 200 whatever the outcome: a remote
 * failure is data ({@code outcome: REMOTE_FAILURE}), not an HTTP error.
 *
 * <p>Errors are ProblemDetail bodies. From the shared GlobalExceptionHandler: 400 for a missing
 * field (field messages under {@code errors}), 404 for an unknown node, 409 "Node down" for a
 * crashed caller (or comparison target), 409 "Node state conflict" when RMI could not start on a
 * comparison target, 409 "Module busy". From this controller, in the same shape: 400 for a method
 * and arguments that do not fit or break a configured limit (every field at once), 404 for an
 * unknown call id. An unreadable body or a non-numeric id gets Spring's default 400, without
 * {@code errors}.</p>
 */
@RestController
@RequestMapping("/api/modules/rmi")
public class RmiController {

    static final String INVALID_PARAMETERS_TITLE = "Invalid request parameters";

    private final RmiModule module;

    public RmiController(RmiModule module) {
        this.module = module;
    }

    /** Status, nodes, methods, limits, texts and the latest call. No remote call. */
    @GetMapping
    public RmiOverviewDto overview() {
        return module.overview();
    }

    /** Starts RMI on every node that is up; idempotent. */
    @PostMapping("/start")
    public RmiOverviewDto start() {
        return module.start();
    }

    /** Every node's registry, listed over the network. */
    @GetMapping("/registries")
    public List<RegistryDto> registries() {
        return module.registries();
    }

    /** One node's registry, listed over the network. */
    @GetMapping("/registries/{nodeId}")
    public RegistryDto registry(@PathVariable int nodeId) {
        return module.registry(nodeId);
    }

    /** One call through the target's registry and a stub; 200 for SUCCESS and REMOTE_FAILURE. */
    @PostMapping("/calls")
    public RmiCallDto call(@Valid @RequestBody CallCommand command) {
        return module.call(command);
    }

    /** The same call made directly, then through RMI. */
    @PostMapping("/comparisons")
    public ComparisonDto compare(@Valid @RequestBody CallCommand command) {
        return module.compare(command);
    }

    /** The kept calls and comparisons, newest first. */
    @GetMapping("/calls")
    public List<HistoryEntryDto> calls() {
        return module.history();
    }

    /** The newest kept call or comparison; 404 when none. */
    @GetMapping("/calls/latest")
    public HistoryEntryDto latest() {
        return module.latest();
    }

    /** One kept call or comparison; 404 for an unknown id. */
    @GetMapping("/calls/{id}")
    public HistoryEntryDto call(@PathVariable long id) {
        return module.entry(id);
    }

    @ExceptionHandler(UnknownCallException.class)
    public ProblemDetail unknownCall(UnknownCallException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Unknown call");
        problem.setProperty("callId", e.callId());
        return problem;
    }

    @ExceptionHandler(InvalidCallException.class)
    public ProblemDetail invalidCall(InvalidCallException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle(INVALID_PARAMETERS_TITLE);
        problem.setProperty("errors", e.errors());
        return problem;
    }
}
