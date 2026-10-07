package com.udcf.modules.multithreading;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadPoolMetricsTest {

    // The two gauge tests moved to MultithreadingMetricsTest with the gauges (E2c).

    private SimpleMeterRegistry registry;
    private ThreadPoolMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ThreadPoolMetrics(registry, 1);
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

    @Test
    @DisplayName("every Exp 2 meter carries its own node_id tag")
    void everyMeterCarriesNodeId() {
        metrics.recordAccepted();
        ThreadPoolMetrics nodeTwo = new ThreadPoolMetrics(registry, 2);
        nodeTwo.recordAccepted();
        DistributedRequest request = new DistributedRequest("m3", 2, WorkloadType.IO_SIMULATED, 5);
        request.markStarted("metrics-worker-1");
        request.markCompleted("done");
        nodeTwo.recordFinished(request);

        assertThat(registry.getMeters()).isNotEmpty()
                .allSatisfy(meter -> assertThat(meter.getId().getTag("node_id"))
                        .as("node_id on %s", meter.getId().getName())
                        .isIn("1", "2"));
        assertThat(registry.get("distributed_requests_total").tag("node_id", "1").tag("outcome", "accepted")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("distributed_requests_total").tag("node_id", "2").tag("outcome", "accepted")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("distributed_request_duration").tag("node_id", "2").timer().count()).isEqualTo(1L);
    }
}
