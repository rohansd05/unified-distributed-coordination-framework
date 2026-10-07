package com.udcf.modules.multithreading;

import com.udcf.modules.multithreading.dto.BatchSubmissionResponse;
import com.udcf.modules.multithreading.dto.GenerateRequestsCommand;
import com.udcf.modules.multithreading.dto.RequestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Experiment 2 — multithreaded request handling on one node.
 *
 * <p>This is the node's front door for client work. Each incoming request is wrapped,
 * registered, and handed to the node's {@link ThreadPoolExecutor}. The submitting thread
 * never performs the work itself, which is why the page can show many requests in flight
 * across distinct worker threads.</p>
 *
 * <p>Rejection is handled as a first-class outcome rather than an exception bubbling to
 * the client: when the bounded queue is full the request is marked REJECTED and counted,
 * which is exactly the backpressure behaviour the experiment is meant to expose.</p>
 *
 * <p><b>Work multiplier.</b> Each request runs {@code payloadSize × workMultiplier} units
 * of work, where the multiplier comes from the node's
 * {@link com.udcf.core.cluster.NodeCapacity} (FAST 1, MEDIUM 2, SLOW 4). The request
 * itself keeps the payload size the caller asked for.</p>
 *
 * <p>Plain class, one per node; the executor it is given must not be shut down while it
 * is in use (see {@link NodeExecutorFactory} for the crash and recover lifecycle).</p>
 */
public class RequestProcessingService {

    private static final Logger log = LoggerFactory.getLogger(RequestProcessingService.class);

    private final ThreadPoolExecutor executor;
    private final WorkloadExecutor workloadExecutor;
    private final RequestRegistry registry;
    private final ThroughputTracker throughputTracker;
    private final ThreadPoolMetrics metrics;
    private final RequestEventPublisher events;
    private final int nodeId;
    private final int workMultiplier;

    public RequestProcessingService(ThreadPoolExecutor executor,
                                    WorkloadExecutor workloadExecutor,
                                    RequestRegistry registry,
                                    ThroughputTracker throughputTracker,
                                    ThreadPoolMetrics metrics,
                                    RequestEventPublisher events,
                                    int nodeId,
                                    int workMultiplier) {
        if (workMultiplier < 1) {
            throw new IllegalArgumentException("workMultiplier must be at least 1, was " + workMultiplier);
        }
        this.executor = executor;
        this.workloadExecutor = workloadExecutor;
        this.registry = registry;
        this.throughputTracker = throughputTracker;
        this.metrics = metrics;
        this.events = events;
        this.nodeId = nodeId;
        this.workMultiplier = workMultiplier;
    }

    /**
     * Submits a batch of independent requests and returns as soon as they are queued.
     *
     * <p>Returning immediately is the point: the caller sees accepted/rejected counts
     * straight away while the pool drains the work in the background, which is what the
     * page animates.</p>
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
        return submitAsync(type, payloadSize).join();
    }

    /**
     * Submits a single request and returns a future for its result.
     *
     * <p>The future always completes, exactly once: with the finished result, with a
     * REJECTED result straight away if the queue is full, or with a FAILED result if
     * {@link #shutdownNow(String)} aborts the request first.</p>
     */
    public CompletableFuture<RequestResult> submitAsync(WorkloadType type, int payloadSize) {
        return enqueue(newRequest(type, payloadSize)).future;
    }

    /**
     * Crash or stop: shuts the executor down for good and interrupts running work, which
     * then ends FAILED ("Interrupted during processing"). Every request still waiting in
     * the queue ends FAILED with {@code reason}; it is counted and published once and its
     * future completes. Work submitted afterwards is rejected.
     *
     * @return the requests this call aborted from the queue
     */
    public List<RequestResult> shutdownNow(String reason) {
        List<RequestResult> aborted = new ArrayList<>();
        for (Runnable queued : executor.shutdownNow()) {
            if (queued instanceof QueuedRequest task) {
                task.abort(reason).ifPresent(aborted::add);
            }
        }
        if (!aborted.isEmpty()) {
            log.warn("Node {} aborted {} queued requests: {}", nodeId, aborted.size(), reason);
        }
        return aborted;
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
        enqueue(request);
        return request;
    }

    private QueuedRequest enqueue(DistributedRequest request) {
        registry.register(request);
        QueuedRequest task = new QueuedRequest(request);

        try {
            executor.execute(task);
            metrics.recordAccepted();
            events.requestAccepted(request.toResult());
        } catch (RejectedExecutionException e) {
            task.future.complete(reject(request));
        }
        return task;
    }

    private RequestResult reject(DistributedRequest request) {
        request.markRejected("Queue full - node at capacity");
        metrics.recordRejected();
        RequestResult result = request.toResult();
        events.requestFinished(result);
        log.warn("Request {} rejected on node {}: queue full", request.getId(), nodeId);
        return result;
    }

    /**
     * Runs on a worker thread. Records timings and never lets an exception escape.
     *
     * <p>Metrics, throughput and the finished event are recorded only by the call that
     * actually ends the request, so a request is never counted twice.</p>
     */
    RequestResult process(DistributedRequest request) {
        if (!request.markStarted(Thread.currentThread().getName())) {
            return request.toResult();   // already ended, for example aborted by shutdownNow
        }
        events.requestStarted(request.toResult());

        boolean ended;
        try {
            String summary = workloadExecutor.execute(request.getType(), request.getPayloadSize() * workMultiplier);
            ended = request.markCompleted(summary);
        } catch (InterruptedException e) {
            // Preserve the interrupt so a shutdown in progress is not swallowed.
            Thread.currentThread().interrupt();
            ended = request.markFailed("Interrupted during processing");
        } catch (RuntimeException e) {
            ended = request.markFailed(e.getClass().getSimpleName() + ": " + e.getMessage());
            log.error("Request {} failed on node {}", request.getId(), nodeId, e);
        }

        RequestResult result = request.toResult();
        if (ended) {
            throughputTracker.record(request.totalMillis());
            metrics.recordFinished(request);
            events.requestFinished(result);
        }
        return result;
    }

    /**
     * The one task type this service puts on the executor, so {@link #shutdownNow(String)}
     * can recognise the requests it drains from the queue.
     */
    private final class QueuedRequest implements Runnable {

        private final DistributedRequest request;
        private final CompletableFuture<RequestResult> future = new CompletableFuture<>();

        private QueuedRequest(DistributedRequest request) {
            this.request = request;
        }

        @Override
        public void run() {
            future.complete(process(request));
        }

        /** Ends a request that never started. Empty if it had already ended. */
        private Optional<RequestResult> abort(String reason) {
            if (!request.markFailed(reason)) {
                future.complete(request.toResult());
                return Optional.empty();
            }
            metrics.recordFinished(request);
            RequestResult result = request.toResult();
            events.requestFinished(result);
            future.complete(result);
            return Optional.of(result);
        }
    }

    public int getNodeId() {
        return nodeId;
    }

    public int getWorkMultiplier() {
        return workMultiplier;
    }
}
