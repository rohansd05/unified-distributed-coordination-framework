package com.udcf.monitoring;

import com.udcf.model.DistributedRequest;
import com.udcf.model.RequestStatus;
import com.udcf.threadpool.ThroughputTracker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Publishes Experiment 2 metrics to Prometheus.
 *
 * <p>Gauges are bound directly to the live {@link ThreadPoolExecutor}, so Prometheus
 * reads the executor's own counters at scrape time. Nothing is cached or estimated.</p>
 *
 * <p>Metric names follow the {@code distributed_*} convention agreed in Section 19 of
 * the project context, so the Grafana dashboards provisioned later need no renaming.</p>
 *
 * <p>Every meter carries its own {@code node_id} tag (decision R5): one JVM hosts several
 * nodes, so a global common tag would mislabel every other node's meters.</p>
 */
@Component
public class ThreadPoolMetrics {

    private final MeterRegistry registry;
    private final ThreadPoolExecutor executor;
    private final ThroughputTracker throughputTracker;
    private final Tags nodeTags;

    public ThreadPoolMetrics(MeterRegistry registry,
                             ThreadPoolExecutor executor,
                             ThroughputTracker throughputTracker,
                             @Value("${udcf.node.id:1}") int nodeId) {
        this.registry = registry;
        this.executor = executor;
        this.throughputTracker = throughputTracker;
        this.nodeTags = Tags.of("node_id", String.valueOf(nodeId));
    }

    @PostConstruct
    void bindGauges() {
        Gauge.builder("distributed_active_threads", executor, ThreadPoolExecutor::getActiveCount)
                .description("Worker threads currently executing a request")
                .tags(nodeTags)
                .register(registry);

        Gauge.builder("distributed_pool_size", executor, ThreadPoolExecutor::getPoolSize)
                .description("Threads currently alive in the pool")
                .tags(nodeTags)
                .register(registry);

        Gauge.builder("distributed_queued_requests", executor, e -> e.getQueue().size())
                .description("Requests waiting in the bounded queue")
                .tags(nodeTags)
                .register(registry);

        Gauge.builder("distributed_queue_remaining_capacity", executor,
                        e -> e.getQueue().remainingCapacity())
                .description("Remaining slots before the pool starts rejecting requests")
                .tags(nodeTags)
                .register(registry);

        Gauge.builder("distributed_request_throughput", throughputTracker,
                        ThroughputTracker::requestsPerSecond)
                .description("Completed requests per second over the sliding window")
                .tags(nodeTags)
                .register(registry);

        Gauge.builder("distributed_response_time_p95_millis", throughputTracker,
                        ThroughputTracker::p95ResponseTimeMillis)
                .description("95th percentile end-to-end request latency")
                .tags(nodeTags)
                .register(registry);
    }

    /** Counts an accepted submission before it reaches a worker thread. */
    public void recordAccepted() {
        registry.counter("distributed_requests_total", nodeTags.and("outcome", "accepted")).increment();
    }

    /** Counts a submission refused because the queue was full. */
    public void recordRejected() {
        registry.counter("distributed_requests_total", nodeTags.and("outcome", "rejected")).increment();
    }

    /** Records the outcome and end-to-end duration of a finished request. */
    public void recordFinished(DistributedRequest request) {
        String outcome = request.getStatus() == RequestStatus.COMPLETED ? "completed" : "failed";
        registry.counter("distributed_requests_total", nodeTags.and("outcome", outcome)).increment();

        Timer.builder("distributed_request_duration")
                .description("End-to-end request duration including queue wait")
                .tag("type", request.getType().name())
                .tag("outcome", outcome)
                .tags(nodeTags)
                .register(registry)
                .record((long) (request.totalMillis() * 1000), TimeUnit.MICROSECONDS);
    }
}
