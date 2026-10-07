package com.udcf.modules.multithreading;

import com.udcf.core.metrics.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Publishes one node's Experiment 2 metrics to Prometheus.
 *
 * <p>Gauges are bound directly to the live {@link ThreadPoolExecutor}, so Prometheus
 * reads the executor's own counters at scrape time. Nothing is cached or estimated.</p>
 *
 * <p>Metric names come from {@link MetricNames} (docs/HANDOFF.md 6.9), so the Grafana
 * dashboards provisioned later need no renaming.</p>
 *
 * <p>Every meter carries its own {@code node_id} tag (decision R5): one JVM hosts several
 * nodes, so a global common tag would mislabel every other node's meters.</p>
 *
 * <p>The owner calls {@link #bindGauges()} once. Micrometer returns an existing gauge
 * when one with the same name and tags is already registered, so binding a second
 * executor for the same node would silently keep reading the first one.</p>
 */
public class ThreadPoolMetrics {

    private final MeterRegistry registry;
    private final ThreadPoolExecutor executor;
    private final ThroughputTracker throughputTracker;
    private final Tags nodeTags;

    public ThreadPoolMetrics(MeterRegistry registry,
                             ThreadPoolExecutor executor,
                             ThroughputTracker throughputTracker,
                             int nodeId) {
        this.registry = registry;
        this.executor = executor;
        this.throughputTracker = throughputTracker;
        this.nodeTags = Tags.of(MetricNames.NODE_ID, String.valueOf(nodeId));
    }

    public void bindGauges() {
        Gauge.builder(MetricNames.ACTIVE_THREADS, executor, ThreadPoolExecutor::getActiveCount)
                .description("Worker threads currently executing a request")
                .tags(nodeTags)
                .register(registry);

        Gauge.builder(MetricNames.POOL_SIZE, executor, ThreadPoolExecutor::getPoolSize)
                .description("Threads currently alive in the pool")
                .tags(nodeTags)
                .register(registry);

        Gauge.builder(MetricNames.QUEUED_REQUESTS, executor, e -> e.getQueue().size())
                .description("Requests waiting in the bounded queue")
                .tags(nodeTags)
                .register(registry);

        Gauge.builder(MetricNames.QUEUE_REMAINING_CAPACITY, executor,
                        e -> e.getQueue().remainingCapacity())
                .description("Remaining slots before the pool starts rejecting requests")
                .tags(nodeTags)
                .register(registry);

        Gauge.builder(MetricNames.REQUEST_THROUGHPUT, throughputTracker,
                        ThroughputTracker::requestsPerSecond)
                .description("Completed requests per second over the sliding window")
                .tags(nodeTags)
                .register(registry);

        Gauge.builder(MetricNames.RESPONSE_TIME_P95_MILLIS, throughputTracker,
                        ThroughputTracker::p95ResponseTimeMillis)
                .description("95th percentile end-to-end request latency")
                .tags(nodeTags)
                .register(registry);
    }

    /** Counts an accepted submission before it reaches a worker thread. */
    public void recordAccepted() {
        registry.counter(MetricNames.REQUESTS_TOTAL, nodeTags.and("outcome", "accepted")).increment();
    }

    /** Counts a submission refused because the queue was full. */
    public void recordRejected() {
        registry.counter(MetricNames.REQUESTS_TOTAL, nodeTags.and("outcome", "rejected")).increment();
    }

    /** Records the outcome and end-to-end duration of a finished request. */
    public void recordFinished(DistributedRequest request) {
        String outcome = request.getStatus() == RequestStatus.COMPLETED ? "completed" : "failed";
        registry.counter(MetricNames.REQUESTS_TOTAL, nodeTags.and("outcome", outcome)).increment();

        Timer.builder(MetricNames.REQUEST_DURATION)
                .description("End-to-end request duration including queue wait")
                .tag("type", request.getType().name())
                .tag("outcome", outcome)
                .tags(nodeTags)
                .register(registry)
                .record((long) (request.totalMillis() * 1000), TimeUnit.MICROSECONDS);
    }
}
