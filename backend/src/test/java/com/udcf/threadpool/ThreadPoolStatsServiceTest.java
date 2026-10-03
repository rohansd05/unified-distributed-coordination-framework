package com.udcf.threadpool;

import com.udcf.config.ThreadPoolProperties;
import com.udcf.dto.ThreadPoolStats;
import com.udcf.model.DistributedRequest;
import com.udcf.model.RequestStatus;
import com.udcf.model.WorkloadType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ThreadPoolStatsServiceTest {

    private ThreadPoolExecutor executor;
    private RequestRegistry registry;
    private ThroughputTracker throughputTracker;
    private ThreadPoolProperties properties;
    private ThreadPoolStatsService service;

    @BeforeEach
    void setUp() {
        properties = new ThreadPoolProperties();
        properties.setCorePoolSize(2);
        properties.setMaxPoolSize(2);
        properties.setQueueCapacity(50);

        executor = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50), new NamedThreadFactory("stats-worker-"),
                new ThreadPoolExecutor.AbortPolicy());
        registry = new RequestRegistry(100);
        throughputTracker = new ThroughputTracker(properties);
        service = new ThreadPoolStatsService(executor, registry, throughputTracker, properties, 3);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("an idle pool reports zero activity, not placeholder values")
    void idlePoolReportsZeros() {
        ThreadPoolStats stats = service.snapshot();

        assertThat(stats.nodeId()).isEqualTo(3);
        assertThat(stats.activeThreads()).isZero();
        assertThat(stats.queuedRequests()).isZero();
        assertThat(stats.completedTasks()).isZero();
        assertThat(stats.requestsPerSecond()).isZero();
        assertThat(stats.queueCapacity()).isEqualTo(50);
        assertThat(stats.corePoolSize()).isEqualTo(2);
        assertThat(stats.maxPoolSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("reads live active-thread and queue depth from the real executor")
    void readsLiveExecutorState() throws InterruptedException {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(2);

        // Occupy both worker threads, then queue two more tasks behind them.
        for (int i = 0; i < 2; i++) {
            executor.execute(() -> {
                started.countDown();
                try {
                    hold.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        executor.execute(() -> { });
        executor.execute(() -> { });

        ThreadPoolStats stats = service.snapshot();

        assertThat(stats.activeThreads()).isEqualTo(2);
        assertThat(stats.queuedRequests()).isEqualTo(2);
        assertThat(stats.queueRemainingCapacity()).isEqualTo(48);

        hold.countDown();
        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> service.snapshot().completedTasks() == 4L);
    }

    @Test
    @DisplayName("includes the registry's status breakdown")
    void includesStatusBreakdown() {
        DistributedRequest completed = new DistributedRequest("a", 3, WorkloadType.CPU_HASH, 5);
        completed.markStarted("stats-worker-1");
        completed.markCompleted("hash=1234");
        registry.register(completed);

        ThreadPoolStats stats = service.snapshot();

        assertThat(stats.statusCounts()).containsEntry(RequestStatus.COMPLETED, 1L);
    }

    @Test
    @DisplayName("surfaces recorded latency statistics")
    void surfacesLatencyStatistics() {
        throughputTracker.record(10d);
        throughputTracker.record(30d);

        ThreadPoolStats stats = service.snapshot();

        assertThat(stats.averageResponseTimeMillis()).isEqualTo(20.0d);
        assertThat(stats.sampleCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("reset clears history and samples without disturbing the executor")
    void resetClearsHistoryOnly() {
        registry.register(new DistributedRequest("a", 3, WorkloadType.CPU_HASH, 5));
        throughputTracker.record(10d);

        service.reset();

        assertThat(registry.size()).isZero();
        assertThat(service.snapshot().sampleCount()).isZero();
        assertThat(executor.isShutdown()).isFalse();
    }
}
