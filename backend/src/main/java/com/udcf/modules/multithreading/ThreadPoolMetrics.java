package com.udcf.modules.multithreading;

import com.udcf.core.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Records one node's Experiment 2 request counters and duration timer.
 *
 * <p>Metric names come from {@link MetricNames} (docs/HANDOFF.md 6.9), so the Grafana
 * dashboards provisioned later need no renaming.</p>
 *
 * <p>Every meter carries its own {@code node_id} tag (decision R5): one JVM hosts several
 * nodes, so a global common tag would mislabel every other node's meters. Counters with the
 * same name and tags are shared, so a node's counts carry on across crash and recovery.</p>
 *
 * <p>The executor gauges are not here: {@link MultithreadingMetrics} binds them once per
 * node, because an executor is replaced on every recovery.</p>
 */
public class ThreadPoolMetrics {

    private final MeterRegistry registry;
    private final Tags nodeTags;

    public ThreadPoolMetrics(MeterRegistry registry, int nodeId) {
        this.registry = registry;
        this.nodeTags = Tags.of(MetricNames.NODE_ID, String.valueOf(nodeId));
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
