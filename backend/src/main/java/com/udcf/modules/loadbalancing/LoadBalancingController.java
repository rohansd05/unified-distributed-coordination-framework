package com.udcf.modules.loadbalancing;

import com.udcf.modules.loadbalancing.dto.CompareCommand;
import com.udcf.modules.loadbalancing.dto.ComparisonDto;
import com.udcf.modules.loadbalancing.dto.LoadBalancingOverviewDto;
import com.udcf.modules.loadbalancing.dto.RunCommand;
import com.udcf.modules.loadbalancing.dto.RunDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Experiment 6's REST API. Thin on purpose: validates, delegates to
 * {@link LoadBalancingModule}, maps to HTTP. Both actions run in the background, so they
 * answer 202 with the action as RUNNING; the overview shows it finish. Errors are
 * ProblemDetail bodies from the shared GlobalExceptionHandler: 400 invalid input, 404 unknown
 * crash node, 409 crash node down or module busy.
 */
@RestController
@RequestMapping("/api/modules/loadbalancing")
public class LoadBalancingController {

    private final LoadBalancingModule module;

    public LoadBalancingController(LoadBalancingModule module) {
        this.module = module;
    }

    /** Status, settings, strategy texts, every worker's live counters and the latest results. */
    @GetMapping
    public LoadBalancingOverviewDto overview() {
        return module.overview();
    }

    /** Runs one strategy, optionally with a crash injected mid-run. */
    @PostMapping("/runs")
    public ResponseEntity<RunDto> run(@Valid @RequestBody RunCommand command) {
        return ResponseEntity.accepted().body(module.startRun(command));
    }

    /** Runs all four strategies on the same batch, after one unreported warm-up batch. */
    @PostMapping("/comparisons")
    public ResponseEntity<ComparisonDto> compare(@RequestBody CompareCommand command) {
        return ResponseEntity.accepted().body(module.startComparison(command));
    }
}
