package com.udcf.modules.multithreading;

import com.udcf.modules.multithreading.dto.RequestResult;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A single unit of client work as it travels through one node's thread pool.
 *
 * <p>Instances are written by the submitting thread and then by the worker thread that
 * picks the request up, while being read concurrently by dashboard polling. Fields are
 * therefore volatile, the status is an AtomicReference, and every transition is checked
 * under one lock, so a request is never observed in two states and ends exactly once.</p>
 *
 * <p>Durations are measured with {@link System#nanoTime()} because it is monotonic;
 * {@link Instant} values are carried separately purely for display.</p>
 */
public class DistributedRequest {

    private final String id;
    private final int nodeId;
    private final WorkloadType type;
    private final int payloadSize;

    private final long submittedAtNanos;
    private final Instant submittedAt;

    private final AtomicReference<RequestStatus> status = new AtomicReference<>(RequestStatus.QUEUED);

    private volatile long startedAtNanos;
    private volatile long completedAtNanos;
    private volatile String threadName;
    private volatile String resultSummary;
    private volatile String errorMessage;

    public DistributedRequest(String id, int nodeId, WorkloadType type, int payloadSize) {
        this(id, nodeId, type, payloadSize, System.nanoTime(), Instant.now());
    }

    /** Package-visible constructor used by tests to inject deterministic timings. */
    DistributedRequest(String id, int nodeId, WorkloadType type, int payloadSize,
                       long submittedAtNanos, Instant submittedAt) {
        this.id = id;
        this.nodeId = nodeId;
        this.type = type;
        this.payloadSize = payloadSize;
        this.submittedAtNanos = submittedAtNanos;
        this.submittedAt = submittedAt;
    }

    /*
     * Transitions. A request moves QUEUED -> PROCESSING -> COMPLETED or FAILED, or straight
     * from QUEUED to FAILED (aborted by a crash) or REJECTED. It ends exactly once: each
     * transition checks the current state and writes its fields under one lock, and returns
     * false, changing nothing, when the request is not in a state it may leave. Callers
     * record metrics and events only when a transition returns true. The status is written
     * last, so a reader that sees a state also sees the fields set with it.
     */

    /** QUEUED to PROCESSING. @return false if the request had already ended (for example aborted) */
    public synchronized boolean markStarted(String threadName) {
        if (status.get() != RequestStatus.QUEUED) {
            return false;
        }
        this.threadName = threadName;
        this.startedAtNanos = System.nanoTime();
        this.status.set(RequestStatus.PROCESSING);
        return true;
    }

    /** PROCESSING to COMPLETED. @return false if the request was not processing */
    public synchronized boolean markCompleted(String resultSummary) {
        if (status.get() != RequestStatus.PROCESSING) {
            return false;
        }
        this.resultSummary = resultSummary;
        return end(RequestStatus.COMPLETED);
    }

    /** QUEUED or PROCESSING to FAILED. @return false if the request had already ended */
    public synchronized boolean markFailed(String errorMessage) {
        if (isEnded()) {
            return false;
        }
        this.errorMessage = errorMessage;
        return end(RequestStatus.FAILED);
    }

    /**
     * QUEUED to REJECTED: refused before execution because the bounded queue was full.
     * Never assigned a thread, so queue wait and processing time stay at zero.
     *
     * @return false if the request was not queued
     */
    public synchronized boolean markRejected(String reason) {
        if (status.get() != RequestStatus.QUEUED) {
            return false;
        }
        this.errorMessage = reason;
        return end(RequestStatus.REJECTED);
    }

    /** True once the request is COMPLETED, FAILED or REJECTED. */
    public boolean isEnded() {
        RequestStatus current = status.get();
        return current != RequestStatus.QUEUED && current != RequestStatus.PROCESSING;
    }

    private boolean end(RequestStatus terminal) {
        this.completedAtNanos = System.nanoTime();
        this.status.set(terminal);
        return true;
    }

    /** Time spent waiting in the queue before a worker picked it up. */
    public double queueWaitMillis() {
        if (startedAtNanos == 0L) {
            return 0d;
        }
        return millisBetween(submittedAtNanos, startedAtNanos);
    }

    /** Time spent actually executing on a worker thread. */
    public double processingMillis() {
        if (startedAtNanos == 0L || completedAtNanos == 0L) {
            return 0d;
        }
        return millisBetween(startedAtNanos, completedAtNanos);
    }

    /** End-to-end latency as a client would perceive it: queue wait plus processing. */
    public double totalMillis() {
        if (completedAtNanos == 0L) {
            return 0d;
        }
        return millisBetween(submittedAtNanos, completedAtNanos);
    }

    private static double millisBetween(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000d;
    }

    public RequestResult toResult() {
        return new RequestResult(
                id,
                nodeId,
                type,
                status.get(),
                threadName,
                submittedAt,
                round(queueWaitMillis()),
                round(processingMillis()),
                round(totalMillis()),
                resultSummary,
                errorMessage
        );
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    public String getId() {
        return id;
    }

    public int getNodeId() {
        return nodeId;
    }

    public WorkloadType getType() {
        return type;
    }

    public int getPayloadSize() {
        return payloadSize;
    }

    public RequestStatus getStatus() {
        return status.get();
    }

    public String getThreadName() {
        return threadName;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
