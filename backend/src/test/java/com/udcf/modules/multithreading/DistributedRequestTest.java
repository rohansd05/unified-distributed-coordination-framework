package com.udcf.modules.multithreading;

import com.udcf.modules.multithreading.dto.RequestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    @DisplayName("a request ends once: a second terminal transition is refused and changes nothing")
    void endsExactlyOnce() {
        DistributedRequest request = newRequest();
        request.markStarted("udcf-worker-1");

        assertThat(request.markCompleted("hash=beef")).isTrue();
        assertThat(request.markFailed("too late")).isFalse();
        assertThat(request.markRejected("too late")).isFalse();
        assertThat(request.markCompleted("again")).isFalse();

        assertThat(request.getStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(request.getResultSummary()).isEqualTo("hash=beef");
        assertThat(request.getErrorMessage()).isNull();
        assertThat(request.isEnded()).isTrue();
    }

    @Test
    @DisplayName("a request aborted while queued can no longer start, complete or be rejected")
    void abortedRequestCannotStart() {
        DistributedRequest request = newRequest();

        assertThat(request.markFailed("Node crashed")).isTrue();
        assertThat(request.markStarted("udcf-worker-1")).isFalse();
        assertThat(request.markCompleted("hash=beef")).isFalse();

        assertThat(request.getStatus()).isEqualTo(RequestStatus.FAILED);
        assertThat(request.getThreadName()).isNull();
        assertThat(request.getErrorMessage()).isEqualTo("Node crashed");
    }

    @Test
    @DisplayName("completing needs a started request, and only a queued request can be rejected")
    void transitionsNeedTheRightState() {
        DistributedRequest queued = newRequest();
        assertThat(queued.markCompleted("hash=beef")).isFalse();
        assertThat(queued.getStatus()).isEqualTo(RequestStatus.QUEUED);
        assertThat(queued.isEnded()).isFalse();

        DistributedRequest running = newRequest();
        running.markStarted("udcf-worker-1");
        assertThat(running.markRejected("Queue full")).isFalse();
        assertThat(running.getStatus()).isEqualTo(RequestStatus.PROCESSING);
    }

    @Test
    @DisplayName("when completion and failure race, exactly one wins")
    void racingTransitionsHaveOneWinner() throws InterruptedException {
        for (int round = 0; round < 200; round++) {
            DistributedRequest request = newRequest();
            request.markStarted("udcf-worker-1");
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            Thread completer = new Thread(() -> {
                awaitQuietly(start);
                if (request.markCompleted("hash=beef")) {
                    winners.incrementAndGet();
                }
            });
            Thread failer = new Thread(() -> {
                awaitQuietly(start);
                if (request.markFailed("Node crashed")) {
                    winners.incrementAndGet();
                }
            });
            completer.start();
            failer.start();
            start.countDown();
            completer.join();
            failer.join();

            assertThat(winners.get()).isEqualTo(1);
            assertThat(request.getStatus()).isIn(RequestStatus.COMPLETED, RequestStatus.FAILED);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
