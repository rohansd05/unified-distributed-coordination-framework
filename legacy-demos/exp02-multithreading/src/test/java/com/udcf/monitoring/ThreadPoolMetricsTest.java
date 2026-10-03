package com.udcf.monitoring;

import com.udcf.config.ThreadPoolProperties;
import com.udcf.model.DistributedRequest;
import com.udcf.model.WorkloadType;
import com.udcf.threadpool.NamedThreadFactory;
import com.udcf.threadpool.ThroughputTracker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadPoolMetricsTest {

    private SimpleMeterRegistry registry;
    private ThreadPoolExecutor executor;
    private ThroughputTracker throughputTracker;
    private ThreadPoolMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        executor = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10), new NamedThreadFactory("metrics-worker-"),
                new ThreadPoolExecutor.AbortPolicy());
        throughputTracker = new ThroughputTracker(new ThreadPoolProperties());
        metrics = new ThreadPoolMetrics(registry, executor, throughputTracker);
        metrics.bindGauges();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("registers the distributed_* gauges the Grafana dashboards expect")
    void registersExpectedGauges() {
        assertThat(registry.find("distributed_active_threads").gauge()).isNotNull();
        assertThat(registry.find("distributed_pool_size").gauge()).isNotNull();
        assertThat(registry.find("distributed_queued_requests").gauge()).isNotNull();
        assertThat(registry.find("distributed_queue_remaining_capacity").gauge()).isNotNull();
        assertThat(registry.find("distributed_request_throughput").gauge()).isNotNull();
        assertThat(registry.find("distributed_response_time_p95_millis").gauge()).isNotNull();
    }

    @Test
    @DisplayName("gauges read live executor state rather than a cached copy")
    void gaugesTrackLiveExecutorState() {
        assertThat(registry.get("distributed_queued_requests").gauge().value()).isZero();

        executor.getQueue().add(() -> { });

        assertThat(registry.get("distributed_queued_requests").gauge().value()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("counts accepted and rejected submissions under separate outcome tags")
    void countsAcceptedAndRejected() {
        metrics.recordAccepted();
        metrics.recordAccepted();
        metrics.recordRejected();

        assertThat(registry.get("distributed_requests_total").tag("outcome", "accepted")
                .counter().count()).isEqualTo(2.0d);
        assertThat(registry.get("distributed_requests_total").tag("outcome", "rejected")
                .counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("records a timer sample tagged with workload type for a completed request")
    void recordsCompletedTimer() {
        DistributedRequest request = new DistributedRequest("m1", 1, WorkloadType.CPU_HASH, 5);
        request.markStarted("metrics-worker-1");
        request.markCompleted("hash=aaaa");

        metrics.recordFinished(request);

        assertThat(registry.get("distributed_request_duration")
                .tag("type", "CPU_HASH")
                .tag("outcome", "completed")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a failed request is tagged as failed, not completed")
    void tagsFailuresSeparately() {
        DistributedRequest request = new DistributedRequest("m2", 1, WorkloadType.MIXED, 5);
        request.markStarted("metrics-worker-1");
        request.markFailed("boom");

        metrics.recordFinished(request);

        assertThat(registry.get("distributed_requests_total").tag("outcome", "failed")
                .counter().count()).isEqualTo(1.0d);
    }
}
