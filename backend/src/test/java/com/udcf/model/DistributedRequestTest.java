package com.udcf.model;

import com.udcf.dto.RequestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the request lifecycle and the timing arithmetic the dashboard depends on.
 * Lives in the model package so it can use the package-visible test constructor.
 */
class DistributedRequestTest {

    private DistributedRequest newRequest() {
        return new DistributedRequest("req-1", 1, WorkloadType.CPU_HASH, 10,
                System.nanoTime(), Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("starts queued with no thread assigned and zero timings")
    void startsQueued() {
        DistributedRequest request = newRequest();

        assertThat(request.getStatus()).isEqualTo(RequestStatus.QUEUED);
        assertThat(request.getThreadName()).isNull();
        assertThat(request.queueWaitMillis()).isZero();
        assertThat(request.processingMillis()).isZero();
        assertThat(request.totalMillis()).isZero();
    }

    @Test
    @DisplayName("records the worker thread when processing begins")
    void recordsWorkerThread() {
        DistributedRequest request = newRequest();

        request.markStarted("udcf-worker-3");

        assertThat(request.getStatus()).isEqualTo(RequestStatus.PROCESSING);
        assertThat(request.getThreadName()).isEqualTo("udcf-worker-3");
        assertThat(request.queueWaitMillis()).isGreaterThanOrEqualTo(0d);
        // Still running, so there is no end-to-end duration yet.
        assertThat(request.totalMillis()).isZero();
    }

    @Test
    @DisplayName("total duration covers queue wait plus processing once complete")
    void totalCoversQueueWaitAndProcessing() throws InterruptedException {
        DistributedRequest request = newRequest();

        Thread.sleep(5);
        request.markStarted("udcf-worker-1");
        Thread.sleep(5);
        request.markCompleted("hash=abcd");

        assertThat(request.getStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(request.getResultSummary()).isEqualTo("hash=abcd");
        assertThat(request.queueWaitMillis()).isGreaterThan(0d);
        assertThat(request.processingMillis()).isGreaterThan(0d);
        assertThat(request.totalMillis())
                .isGreaterThanOrEqualTo(request.queueWaitMillis() + request.processingMillis() - 1d);
    }

    @Test
    @DisplayName("failure captures the error and still produces a duration")
    void failureCapturesError() {
        DistributedRequest request = newRequest();

        request.markStarted("udcf-worker-2");
        request.markFailed("boom");

        assertThat(request.getStatus()).isEqualTo(RequestStatus.FAILED);
        assertThat(request.getErrorMessage()).isEqualTo("boom");
        assertThat(request.totalMillis()).isGreaterThanOrEqualTo(0d);
    }

    @Test
    @DisplayName("rejected requests never get a thread or processing time")
    void rejectedNeverProcesses() {
        DistributedRequest request = newRequest();

        request.markRejected("Queue full");

        assertThat(request.getStatus()).isEqualTo(RequestStatus.REJECTED);
        assertThat(request.getThreadName()).isNull();
        assertThat(request.processingMillis()).isZero();
        assertThat(request.queueWaitMillis()).isZero();
    }

    @Test
    @DisplayName("maps every field onto the serialisable result")
    void mapsToResult() {
        DistributedRequest request = newRequest();
        request.markStarted("udcf-worker-1");
        request.markCompleted("hash=beef");

        RequestResult result = request.toResult();

        assertThat(result.id()).isEqualTo("req-1");
        assertThat(result.nodeId()).isEqualTo(1);
        assertThat(result.type()).isEqualTo(WorkloadType.CPU_HASH);
        assertThat(result.status()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(result.threadName()).isEqualTo("udcf-worker-1");
        assertThat(result.resultSummary()).isEqualTo("hash=beef");
        assertThat(result.submittedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }
}
