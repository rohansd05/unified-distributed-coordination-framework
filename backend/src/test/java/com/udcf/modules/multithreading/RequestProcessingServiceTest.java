package com.udcf.modules.multithreading;

import com.udcf.modules.multithreading.dto.BatchSubmissionResponse;
import com.udcf.modules.multithreading.dto.GenerateRequestsCommand;
import com.udcf.modules.multithreading.dto.RequestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
}
