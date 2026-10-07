package com.udcf.modules.multithreading;

import com.udcf.modules.multithreading.dto.BatchSubmissionResponse;
import com.udcf.modules.multithreading.dto.GenerateRequestsCommand;
import com.udcf.modules.multithreading.dto.RequestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the core of Experiment 2 against a real executor. A mocked executor would
 * prove nothing here — the point is that work genuinely runs on pool threads.
 */
class RequestProcessingServiceTest {

    private ThreadPoolExecutor executor;
    private RequestRegistry registry;
    private ThroughputTracker throughputTracker;
    private ThreadPoolMetrics metrics;
    private RequestEventPublisher events;
    private RequestProcessingService service;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolExecutor(4, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100), new NamedThreadFactory("test-worker-"),
                new ThreadPoolExecutor.AbortPolicy());
        registry = new RequestRegistry(500);
        throughputTracker = new ThroughputTracker(30);
        metrics = mock(ThreadPoolMetrics.class);
        events = mock(RequestEventPublisher.class);
        service = new RequestProcessingService(executor, new WorkloadExecutor(), registry,
                throughputTracker, metrics, events, 2, 1);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("a synchronous request completes and reports the worker thread that ran it")
    void synchronousRequestCompletes() {
        RequestResult result = service.submitAndWait(WorkloadType.CPU_HASH, 10);

        assertThat(result.status()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(result.threadName()).startsWith("test-worker-");
        assertThat(result.nodeId()).isEqualTo(2);
        assertThat(result.resultSummary()).startsWith("hash=");
        assertThat(result.totalMillis()).isGreaterThan(0d);
    }

    @Test
    @DisplayName("work runs off the calling thread, not on it")
    void workRunsOffTheCallingThread() {
        String callerThread = Thread.currentThread().getName();

        RequestResult result = service.submitAndWait(WorkloadType.CPU_HASH, 5);

        assertThat(result.threadName()).isNotEqualTo(callerThread);
    }

    @Test
    @DisplayName("a batch is accepted immediately and drains across multiple threads")
    void batchDrainsAcrossMultipleThreads() {
        BatchSubmissionResponse response =
                service.submitBatch(GenerateRequestsCommand.of(24, WorkloadType.MIXED, 10));

        assertThat(response.requested()).isEqualTo(24);
        assertThat(response.accepted()).isEqualTo(24);
        assertThat(response.rejected()).isZero();
        assertThat(response.requestIds()).hasSize(24).doesNotHaveDuplicates();

        await().atMost(30, TimeUnit.SECONDS).until(() ->
                registry.countByStatus().get(RequestStatus.COMPLETED) == 24L);

        Set<String> threadsUsed = ConcurrentHashMap.newKeySet();
        registry.recent(24).forEach(request -> threadsUsed.add(request.getThreadName()));

        // More than one worker thread handled the batch: this is the experiment's claim.
        assertThat(threadsUsed).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("completed requests are recorded for throughput and metrics")
    void recordsThroughputAndMetrics() {
        service.submitAndWait(WorkloadType.CPU_HASH, 5);

        assertThat(throughputTracker.sampleCount()).isEqualTo(1);
        verify(metrics).recordAccepted();
        verify(metrics).recordFinished(any());
        verify(metrics, never()).recordRejected();
    }

    @Test
    @DisplayName("publishes accepted, started and finished lifecycle events")
    void publishesLifecycleEvents() {
        service.submitAndWait(WorkloadType.CPU_HASH, 5);

        verify(events, atLeastOnce()).requestAccepted(any());
        verify(events, atLeastOnce()).requestStarted(any());
        verify(events, atLeastOnce()).requestFinished(any());
    }

    @Test
    @DisplayName("a request refused by a saturated pool is marked REJECTED, not thrown")
    void rejectsWhenPoolCannotAccept() {
        // Shutting the executor down is a deterministic way to force rejection.
        executor.shutdown();

        RequestResult result = service.submitAndWait(WorkloadType.CPU_HASH, 5);

        assertThat(result.status()).isEqualTo(RequestStatus.REJECTED);
        assertThat(result.errorMessage()).contains("Queue full");
        assertThat(result.threadName()).isNull();
        verify(metrics).recordRejected();
    }

    @Test
    @DisplayName("a rejected batch still reports accurate accepted and rejected counts")
    void batchReportsRejections() {
        executor.shutdown();

        BatchSubmissionResponse response =
                service.submitBatch(GenerateRequestsCommand.of(5, WorkloadType.CPU_HASH, 5));

        assertThat(response.accepted()).isZero();
        assertThat(response.rejected()).isEqualTo(5);
    }

    @Test
    @DisplayName("every submitted request is tracked in the registry")
    void tracksEverySubmission() {
        service.submitBatch(GenerateRequestsCommand.of(6, WorkloadType.CPU_HASH, 5));

        assertThat(registry.size()).isEqualTo(6);
    }

    @Test
    @DisplayName("the node's work multiplier scales the work done, not the payload the caller asked for")
    void workMultiplierScalesTheWork() throws InterruptedException {
        WorkloadExecutor workload = mock(WorkloadExecutor.class);
        when(workload.execute(any(), anyInt())).thenReturn("hash=stub");
        RequestProcessingService slowNode = new RequestProcessingService(executor, workload, registry,
                throughputTracker, metrics, events, 3, 4);

        RequestResult result = slowNode.submitAndWait(WorkloadType.CPU_HASH, 5);

        verify(workload).execute(eq(WorkloadType.CPU_HASH), eq(20));
        assertThat(result.status()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(registry.find(result.id())).get()
                .extracting(DistributedRequest::getPayloadSize).isEqualTo(5);
        assertThat(slowNode.getWorkMultiplier()).isEqualTo(4);
    }

    @Test
    @DisplayName("a work multiplier below one is refused")
    void rejectsMultiplierBelowOne() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new RequestProcessingService(executor, new WorkloadExecutor(), registry,
                        throughputTracker, metrics, events, 1, 0));
    }

    @Test
    @DisplayName("submitAsync returns at once and completes on a worker thread")
    void submitAsyncCompletesOnAWorker() throws Exception {
        CompletableFuture<RequestResult> future = service.submitAsync(WorkloadType.CPU_HASH, 5);

        RequestResult result = future.get(10, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(result.threadName()).startsWith("test-worker-")
                .isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    @DisplayName("submitAsync on a pool that cannot accept returns an already completed REJECTED result")
    void submitAsyncRejectsAtOnce() {
        executor.shutdown();

        CompletableFuture<RequestResult> future = service.submitAsync(WorkloadType.CPU_HASH, 5);

        assertThat(future).isDone();
        assertThat(future.join().status()).isEqualTo(RequestStatus.REJECTED);
        verify(metrics).recordRejected();
    }

    @Test
    @DisplayName("shutdownNow ends every queued request FAILED with the reason and completes its future")
    void shutdownNowAbortsQueuedRequests() {
        occupyAllWorkers();
        List<CompletableFuture<RequestResult>> queued = List.of(
                service.submitAsync(WorkloadType.CPU_HASH, 5),
                service.submitAsync(WorkloadType.CPU_HASH, 5),
                service.submitAsync(WorkloadType.CPU_HASH, 5));

        List<RequestResult> aborted = service.shutdownNow("Node crashed");

        assertThat(aborted).hasSize(3).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(RequestStatus.FAILED);
            assertThat(result.errorMessage()).isEqualTo("Node crashed");
            assertThat(result.threadName()).isNull();
        });
        assertThat(queued).allSatisfy(future -> {
            assertThat(future).isDone();
            assertThat(future.join().status()).isEqualTo(RequestStatus.FAILED);
        });
        assertThat(executor.isShutdown()).isTrue();
    }

    @Test
    @DisplayName("shutdownNow interrupts running work, which ends FAILED, interrupted during processing")
    void shutdownNowInterruptsRunningWork() {
        List<CompletableFuture<RequestResult>> running = occupyAllWorkers();

        service.shutdownNow("Node crashed");

        CompletableFuture.allOf(running.toArray(CompletableFuture[]::new)).orTimeout(10, TimeUnit.SECONDS).join();
        assertThat(running).allSatisfy(future -> {
            assertThat(future.join().status()).isEqualTo(RequestStatus.FAILED);
            assertThat(future.join().errorMessage()).isEqualTo("Interrupted during processing");
        });
    }

    @Test
    @DisplayName("a crash while many requests complete ends each exactly once: one status, one count, one event, one future")
    void crashWhileCompletingEndsEachRequestOnce() {
        int requests = 300;
        int completedOverall = 0;
        int abortedOverall = 0;

        for (int round = 0; round < 20; round++) {
            // Each request completes when it gets a permit; a releaser hands them out one at a
            // time, so completions are still happening when the crash lands, whatever the JIT does.
            Semaphore permits = new Semaphore(0);
            WorkloadExecutor gated = new WorkloadExecutor() {
                @Override
                public String execute(WorkloadType type, int payloadSize) throws InterruptedException {
                    permits.acquire();
                    return "hash=gated";
                }
            };
            Thread releaser = Thread.ofPlatform().daemon().start(() -> {
                for (int i = 0; i < requests && !Thread.currentThread().isInterrupted(); i++) {
                    permits.release();
                    LockSupport.parkNanos(200_000);
                }
            });
            ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 60, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(requests), new NamedThreadFactory("race-worker-"),
                    new ThreadPoolExecutor.AbortPolicy());
            RequestRegistry history = new RequestRegistry(requests);
            ThreadPoolMetrics counted = mock(ThreadPoolMetrics.class);
            Map<String, AtomicInteger> finishedEvents = new ConcurrentHashMap<>();
            RequestEventPublisher publisher = new RequestEventPublisher() {
                @Override
                public void requestAccepted(RequestResult result) {
                }

                @Override
                public void requestStarted(RequestResult result) {
                }

                @Override
                public void requestFinished(RequestResult result) {
                    finishedEvents.computeIfAbsent(result.id(), id -> new AtomicInteger()).incrementAndGet();
                }
            };
            RequestProcessingService node = new RequestProcessingService(pool, gated, history,
                    new ThroughputTracker(30), counted, publisher, 1, 1);
            try {
                List<CompletableFuture<RequestResult>> futures = new ArrayList<>();
                for (int i = 0; i < requests; i++) {
                    futures.add(node.submitAsync(WorkloadType.CPU_HASH, 1));
                }
                await().atMost(10, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(1))
                        .until(() -> history.countByStatus().get(RequestStatus.COMPLETED) >= 10);

                List<RequestResult> aborted = node.shutdownNow("Node crashed");

                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).orTimeout(10, TimeUnit.SECONDS).join();
                List<DistributedRequest> all = history.recent(0);
                assertThat(all).hasSize(requests).allMatch(DistributedRequest::isEnded);
                assertThat(finishedEvents).hasSize(requests)
                        .allSatisfy((id, count) -> assertThat(count.get()).as("finished events for %s", id).isEqualTo(1));
                verify(counted, times(requests)).recordAccepted();
                verify(counted, times(requests)).recordFinished(any());
                verify(counted, never()).recordRejected();
                for (CompletableFuture<RequestResult> future : futures) {
                    RequestResult result = future.join();
                    assertThat(history.find(result.id())).get()
                            .extracting(DistributedRequest::getStatus).isEqualTo(result.status());
                }
                assertThat(aborted).allMatch(result -> "Node crashed".equals(result.errorMessage()));
                completedOverall += history.countByStatus().get(RequestStatus.COMPLETED);
                abortedOverall += aborted.size();
            } finally {
                releaser.interrupt();
                pool.shutdownNow();
            }
        }

        // The race really happened: some requests finished and some were cut off by the crash.
        assertThat(completedOverall).isPositive();
        assertThat(abortedOverall).isPositive();
    }

    /** Blocks all 4 workers on long IO waits and returns their futures. */
    private List<CompletableFuture<RequestResult>> occupyAllWorkers() {
        List<CompletableFuture<RequestResult>> running = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            running.add(service.submitAsync(WorkloadType.IO_SIMULATED, 5000));   // 2 s each
        }
        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> registry.countByStatus().get(RequestStatus.PROCESSING) == 4L);
        return running;
    }
}
