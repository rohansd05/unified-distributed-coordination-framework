package com.udcf.controller;

import com.udcf.dto.BatchSubmissionResponse;
import com.udcf.dto.GenerateRequestsCommand;
import com.udcf.dto.RequestResult;
import com.udcf.dto.ThreadPoolStats;
import com.udcf.model.DistributedRequest;
import com.udcf.model.WorkloadType;
import com.udcf.threadpool.RequestProcessingService;
import com.udcf.threadpool.RequestRegistry;
import com.udcf.threadpool.ThreadPoolStatsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST surface for the Multithreading page of the dashboard.
 *
 * <p>Kept thin on purpose: it validates input, delegates, and maps to HTTP. All
 * concurrency logic lives in the service layer so it can be unit tested without a
 * servlet container.</p>
 */
@RestController
@RequestMapping("/api/multithreading")
public class MultithreadingController {

    private final RequestProcessingService processingService;
    private final ThreadPoolStatsService statsService;
    private final RequestRegistry registry;

    public MultithreadingController(RequestProcessingService processingService,
                                    ThreadPoolStatsService statsService,
                                    RequestRegistry registry) {
        this.processingService = processingService;
        this.statsService = statsService;
        this.registry = registry;
    }

    /** Generates a batch of concurrent requests. Returns 202: work continues in the pool. */
    @PostMapping("/requests")
    public ResponseEntity<BatchSubmissionResponse> generate(
            @Valid @RequestBody GenerateRequestsCommand command) {
        return ResponseEntity.accepted().body(processingService.submitBatch(command));
    }

    /** Runs a single request and waits for its result. */
    @PostMapping("/requests/sync")
    public ResponseEntity<RequestResult> runOne(
            @RequestParam(defaultValue = "MIXED") WorkloadType type,
            @RequestParam(defaultValue = "50") int payloadSize) {
        return ResponseEntity.ok(processingService.submitAndWait(type, payloadSize));
    }

    /** Most recent requests, newest first. */
    @GetMapping("/requests")
    public List<RequestResult> recent(@RequestParam(defaultValue = "50") int limit) {
        return registry.recent(limit).stream()
                .map(DistributedRequest::toResult)
                .toList();
    }

    @GetMapping("/requests/{id}")
    public ResponseEntity<RequestResult> byId(@PathVariable String id) {
        return registry.find(id)
                .map(request -> ResponseEntity.ok(request.toResult()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Live executor state for the dashboard cards and charts. */
    @GetMapping("/stats")
    public ThreadPoolStats stats() {
        return statsService.snapshot();
    }

    /** Clears request history so a demonstration can start from a clean slate. */
    @DeleteMapping("/requests")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset() {
        statsService.reset();
    }
}
