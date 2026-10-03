package com.udcf.model;

import com.udcf.dto.RequestResult;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A single unit of client work as it travels through one node's thread pool.
 *
 * <p>Instances are written by the submitting thread and then by the worker thread that
 * picks the request up, while being read concurrently by dashboard polling. Fields are
 * therefore volatile and status transitions go through an AtomicReference, so a request
 * can never be observed in two states at once.</p>
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

    public void markStarted(String threadName) {
        this.threadName = threadName;
        this.startedAtNanos = System.nanoTime();
        this.status.set(RequestStatus.PROCESSING);
    }

    public void markCompleted(String resultSummary) {
        this.resultSummary = resultSummary;
        this.completedAtNanos = System.nanoTime();
        this.status.set(RequestStatus.COMPLETED);
    }

    public void markFailed(String errorMessage) {
        this.errorMessage = errorMessage;
        this.completedAtNanos = System.nanoTime();
        this.status.set(RequestStatus.FAILED);
    }

    /**
     * Refused before execution because the bounded queue was full.
     * Never assigned a thread, so queue wait and processing time stay at zero.
     */
    public void markRejected(String reason) {
        this.errorMessage = reason;
        this.completedAtNanos = System.nanoTime();
        this.status.set(RequestStatus.REJECTED);
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
