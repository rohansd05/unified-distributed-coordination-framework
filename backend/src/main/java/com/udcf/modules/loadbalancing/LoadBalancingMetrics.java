package com.udcf.modules.loadbalancing;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.metrics.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Experiment 6 meters, each tagged with {@code node_id} (R5):
 * <ul>
 *   <li>{@value MetricNames#BALANCER_DISPATCHES_TOTAL} {node_id = worker, strategy, outcome
 *       served|failed|declined}: dispatch attempts, added from each worker's counters when a
 *       run ends (the module adds each run once, also a failed one).</li>
 *   <li>{@value MetricNames#BALANCER_MAKESPAN} {node_id = "0", strategy}: each finished run's
 *       makespan (cluster level).</li>
 *   <li>{@value MetricNames#BALANCER_IN_FLIGHT} {node_id}: requests the balancer has in flight
 *       to that node, read from the live worker at scrape time; 0 when no run is active or none
 *       has happened. Never NaN.</li>
 * </ul>
 *
 * <p>Re-creating this class against the same registry (a second module instance, or a test)
 * replaces each in-flight gauge instead of registering a duplicate, so the gauge always reads
 * the newest module's workers; counters and timers are shared by name and tags. Plain class,
 * owned by {@link LoadBalancingModule}. Covered by LoadBalancingMetricsTest.</p>
 */
public class LoadBalancingMetrics {

    static final String STRATEGY = "strategy";
    static final String OUTCOME = "outcome";

    private final MeterRegistry registry;

    public LoadBalancingMetrics(MeterRegistry registry, Cluster cluster, Supplier<List<WorkerInfo>> currentWorkers) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(cluster, "cluster must not be null");
        Objects.requireNonNull(currentWorkers, "currentWorkers must not be null");
        for (ClusterNode node : cluster.nodes()) {
            int nodeId = node.id();
            String tag = String.valueOf(nodeId);
            registry.find(MetricNames.BALANCER_IN_FLIGHT).tag(MetricNames.NODE_ID, tag).meters()
                    .forEach(registry::remove);
            Gauge.builder(MetricNames.BALANCER_IN_FLIGHT, () -> inFlight(currentWorkers.get(), nodeId))
                    .description("Requests the load balancer has in flight to this worker")
                    .tag(MetricNames.NODE_ID, tag)
                    .register(registry);
        }
    }

    /**
     * Adds one run: every worker's served, failed and declined attempt counts, and the
     * makespan if the run finished.
     *
     * @param makespanMillis null for a run that failed (no makespan was measured)
     */
    public void recordRun(Strategy strategy, List<WorkerInfo> workers, Double makespanMillis) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        Objects.requireNonNull(workers, "workers must not be null");
        for (WorkerInfo w : workers) {
            Tags tags = Tags.of(MetricNames.NODE_ID, String.valueOf(w.nodeId()), STRATEGY, strategy.name());
            registry.counter(MetricNames.BALANCER_DISPATCHES_TOTAL, tags.and(OUTCOME, "served")).increment(w.completed());
            registry.counter(MetricNames.BALANCER_DISPATCHES_TOTAL, tags.and(OUTCOME, "failed")).increment(w.failed());
            registry.counter(MetricNames.BALANCER_DISPATCHES_TOTAL, tags.and(OUTCOME, "declined")).increment(w.declined());
        }
        if (makespanMillis != null) {
            Timer.builder(MetricNames.BALANCER_MAKESPAN)
                    .description("Time from the first request of a load balancing run to its last answer")
                    .tag(MetricNames.NODE_ID, MetricNames.CLUSTER_NODE_ID)
                    .tag(STRATEGY, strategy.name())
                    .register(registry)
                    .record(Duration.ofNanos(Math.round(makespanMillis * 1_000_000d)));
        }
    }

    private static int inFlight(List<WorkerInfo> workers, int nodeId) {
        for (WorkerInfo w : workers) {
            if (w.nodeId() == nodeId) {
                return w.inFlight();
            }
        }
        return 0;
    }
}
