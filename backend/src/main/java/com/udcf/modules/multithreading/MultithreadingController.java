package com.udcf.modules.multithreading;

import com.udcf.modules.multithreading.dto.BatchDto;
import com.udcf.modules.multithreading.dto.GenerateRequestsCommand;
import com.udcf.modules.multithreading.dto.MultithreadingOverviewDto;
import com.udcf.modules.multithreading.dto.RequestResult;
import com.udcf.web.InvalidParameterException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Experiment 2's REST API. Thin on purpose: validates, delegates to
 * {@link MultithreadingModule}, maps to HTTP. Errors are ProblemDetail bodies from the shared
 * GlobalExceptionHandler: 404 unknown node, 409 node down or module busy, 400 invalid input.
 */
@RestController
@RequestMapping("/api/modules/multithreading")
public class MultithreadingController {

    private final MultithreadingModule module;
    private final MultithreadingProperties properties;

    public MultithreadingController(MultithreadingModule module, MultithreadingProperties properties) {
        this.module = module;
        this.properties = properties;
    }

    /** Module status, workload labels and every node's live executor snapshot. */
    @GetMapping
    public MultithreadingOverviewDto overview() {
        return module.overview();
    }

    /** Sends a burst of requests to one node; 202, because the work drains in the background. */
    @PostMapping("/nodes/{nodeId}/batches")
    public ResponseEntity<BatchDto> submitBatch(@PathVariable int nodeId,
                                                @Valid @RequestBody GenerateRequestsCommand command) {
        return ResponseEntity.accepted().body(module.submitBatch(nodeId, command));
    }

    /** Overflows one node's queue so some requests are rejected; 409 while a demo is running. */
    @PostMapping("/nodes/{nodeId}/backpressure")
    public ResponseEntity<BatchDto> backpressure(@PathVariable int nodeId) {
        return ResponseEntity.accepted().body(module.backpressure(nodeId));
    }

    /**
     * The node's most recent requests, newest first, with the worker thread that ran each.
     *
     * @param limit 1 to the configured request history size
     */
    @GetMapping("/nodes/{nodeId}/requests")
    public List<RequestResult> requests(@PathVariable int nodeId,
                                        @RequestParam(defaultValue = "50") @Min(1) int limit) {
        if (limit > properties.requestHistorySize()) {
            throw new InvalidParameterException("limit",
                    "must be less than or equal to " + properties.requestHistorySize());
        }
        return module.requests(nodeId, limit);
    }
}
