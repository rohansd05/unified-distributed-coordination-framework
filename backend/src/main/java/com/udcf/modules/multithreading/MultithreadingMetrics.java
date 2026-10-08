package com.udcf.modules.multithreading;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.metrics.MetricNames;
import com.udcf.modules.multithreading.dto.ThreadPoolStats;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.function.ToDoubleFunction;

/**
 * The Experiment 2 executor gauges, six per node, each tagged with its node's {@code node_id}
 * (R5): active threads, pool size, queued requests, remaining queue capacity, throughput and
 * p95 latency.
 *
 * <p>Each gauge is registered once, at startup, against the {@link ClusterNode} (which lives
 * as long as the application) and reads the node's current requests service at scrape time
 * through {@link RequestsNodeService#snapshot()}. An executor is replaced on every recovery,
 * so a gauge bound to one executor would go on reading a dead one (R7); this one follows the
 * node. Reading a gauge never starts a service.</p>
 *
 * <p>While a node's requests service is not running (never started, or crashed) there is no
 * executor to read, and every one of its gauges reports NaN: Prometheus shows "no value",
 * never an invented 0.</p>
 */
@Component
public class MultithreadingMetrics {

    public MultithreadingMetrics(Cluster cluster, MeterRegistry registry) {
        for (ClusterNode node : cluster.nodes()) {
            bind(registry, node, MetricNames.ACTIVE_THREADS, "Worker threads currently executing a request",
                    ThreadPoolStats::activeThreads);
            bind(registry, node, MetricNames.POOL_SIZE, "Threads currently alive in the pool",
                    ThreadPoolStats::poolSize);
            bind(registry, node, MetricNames.QUEUED_REQUESTS, "Requests waiting in the bounded queue",
                    ThreadPoolStats::queuedRequests);
            bind(registry, node, MetricNames.QUEUE_REMAINING_CAPACITY,
                    "Remaining slots before the pool starts rejecting requests",
                    ThreadPoolStats::queueRemainingCapacity);
            bind(registry, node, MetricNames.REQUEST_THROUGHPUT,
                    "Completed requests per second over the sliding window",
                    ThreadPoolStats::requestsPerSecond);
            bind(registry, node, MetricNames.RESPONSE_TIME_P95_MILLIS, "95th percentile end-to-end request latency",
                    ThreadPoolStats::p95ResponseTimeMillis);
        }
    }

    /** The node's current value for {@code reading}, or NaN while it has no running executor. */
    static double read(ClusterNode node, ToDoubleFunction<ThreadPoolStats> reading) {
        return RequestsNodeService.find(node)
                .flatMap(RequestsNodeService::snapshot)
                .map(reading::applyAsDouble)
                .orElse(Double.NaN);
    }

    private static void bind(MeterRegistry registry, ClusterNode node, String name, String description,
                             ToDoubleFunction<ThreadPoolStats> reading) {
        Gauge.builder(name, node, n -> read(n, reading))
                .description(description)
                .tag(MetricNames.NODE_ID, String.valueOf(node.id()))
                .register(registry);
    }
}
