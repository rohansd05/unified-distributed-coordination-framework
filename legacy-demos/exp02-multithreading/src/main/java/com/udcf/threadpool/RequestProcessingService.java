package com.udcf.threadpool;

import com.udcf.dto.BatchSubmissionResponse;
import com.udcf.dto.GenerateRequestsCommand;
import com.udcf.dto.RequestResult;
import com.udcf.model.DistributedRequest;
import com.udcf.model.RequestStatus;
import com.udcf.model.WorkloadType;
import com.udcf.monitoring.ThreadPoolMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Experiment 2 — multithreaded request handling.
 *
 * <p>This is the node's front door for client work. Each incoming request is wrapped,
 * registered, and handed to the shared {@link ThreadPoolExecutor}. The HTTP thread never
 * performs the work itself, which is why the dashboard can show many requests in flight
 * across distinct worker threads.</p>
 *
 * <p>Rejection is handled as a first-class outcome rather than an exception bubbling to
 * the client: when the bounded queue is full the request is marked REJECTED and counted,
 * which is exactly the backpressure behaviour the experiment is meant to expose.</p>
 */
@Service
public class RequestProcessingService {

    private static final Logger log = LoggerFactory.getLogger(RequestProcessingService.class);

    private final ThreadPoolExecutor executor;
    private final WorkloadExecutor workloadExecutor;
    private final RequestRegistry registry;
    private final ThroughputTracker throughputTracker;
    private final ThreadPoolMetrics metrics;
    private final RequestEventPublisher events;
    private final int nodeId;

    public RequestProcessingService(ThreadPoolExecutor executor,
                                    WorkloadExecutor workloadExecutor,
                                    RequestRegistry registry,
                                    ThroughputTracker throughputTracker,
                                    ThreadPoolMetrics metrics,
                                    RequestEventPublisher events,
                                    @Value("${udcf.node.id:1}") int nodeId) {
        this.executor = executor;
        this.workloadExecutor = workloadExecutor;
        this.registry = registry;
        this.throughputTracker = throughputTracker;
        this.metrics = metrics;
        this.events = events;
        this.nodeId = nodeId;
    }

    /**
     * Submits a batch of independent requests and returns as soon as they are queued.
     *
     * <p>Returning immediately is the point: the caller sees accepted/rejected counts
     * straight away while the pool drains the work in the background, which is what the
     * dashboard animates.</p>
     */
    public BatchSubmissionResponse submitBatch(GenerateRequestsCommand command) {
        List<String> ids = new ArrayList<>(command.count());
        int accepted = 0;
        int rejected = 0;

        for (int i = 0; i < command.count(); i++) {
            DistributedRequest request = newRequest(command.type(), command.payloadSize());
            ids.add(request.getId());

            // Rejection is decided synchronously inside submit(), so this check is race-free:
            // an accepted request can only be QUEUED, PROCESSING or COMPLETED by now.
            if (submit(request).getStatus() == RequestStatus.REJECTED) {
                rejected++;
            } else {
                accepted++;
            }
        }

        log.info("Batch submitted on node {}: requested={} accepted={} rejected={}",
                nodeId, command.count(), accepted, rejected);

        return new BatchSubmissionResponse(command.count(), accepted, rejected, ids);
    }

    /**
     * Submits a single request and blocks until it finishes.
     *
     * <p>Used by the UI's "run one request" control, where the caller wants the result
     * rather than a receipt. The work still runs on a pool thread, so joining here does
     * not defeat the thread pool.</p>
     */
    public RequestResult submitAndWait(WorkloadType type, int payloadSize) {
        DistributedRequest request = newRequest(type, payloadSize);
        registry.register(request);

        try {
            CompletableFuture<RequestResult> future =
                    CompletableFuture.supplyAsync(() -> process(request), executor);

            metrics.recordAccepted();
            events.requestAccepted(request.toResult());
            return future.join();
        } catch (RejectedExecutionException e) {
            return reject(request);
        }
    }

    private DistributedRequest newRequest(WorkloadType type, int payloadSize) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return new DistributedRequest(id, nodeId, type, payloadSize);
    }

    /**
     * Registers the request and hands it to the executor.
     * Returns the same instance so the caller can inspect the resulting status.
     */
    DistributedRequest submit(DistributedRequest request) {
        registry.register(request);

        try {
            executor.execute(() -> process(request));
            metrics.recordAccepted();
            events.requestAccepted(request.toResult());
        } catch (RejectedExecutionException e) {
            reject(request);
        }
        return request;
    }

    private RequestResult reject(DistributedRequest request) {
        request.markRejected("Queue full - node at capacity");
        metrics.recordRejected();
        RequestResult result = request.toResult();
        events.requestFinished(result);
        log.warn("Request {} rejected on node {}: queue full", request.getId(), nodeId);
        return result;
    }

    /** Runs on a worker thread. Records timings and never lets an exception escape. */
    RequestResult process(DistributedRequest request) {
        request.markStarted(Thread.currentThread().getName());
        events.requestStarted(request.toResult());

        try {
            String summary = workloadExecutor.execute(request.getType(), request.getPayloadSize());
            request.markCompleted(summary);
        } catch (InterruptedException e) {
            // Preserve the interrupt so a shutdown in progress is not swallowed.
            Thread.currentThread().interrupt();
            request.markFailed("Interrupted during processing");
        } catch (RuntimeException e) {
            request.markFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.error("Request {} failed on node {}", request.getId(), nodeId, e);
        }

        throughputTracker.record(request.totalMillis());
        metrics.recordFinished(request);

        RequestResult result = request.toResult();
        events.requestFinished(result);
        return result;
    }

    public int getNodeId() {
        return nodeId;
    }
}
