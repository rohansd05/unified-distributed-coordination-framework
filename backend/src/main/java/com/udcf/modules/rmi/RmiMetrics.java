package com.udcf.modules.rmi;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.metrics.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;

/**
 * Experiment 1 meters. {@code node_id} is always the <b>calling</b> node (R5), and the called node
 * is the separate {@code target_node} tag. Every name always carries the same tag keys:
 * <ul>
 *   <li>{@value MetricNames#RMI_CALLS_TOTAL} {node_id, target_node, method, path, outcome}: every
 *       call, the LOCAL half of a comparison included.</li>
 *   <li>{@value MetricNames#RMI_FAILURES_TOTAL} {node_id, target_node, method, stage, kind}: every
 *       remote failure.</li>
 *   <li>{@value MetricNames#RMI_ROUND_TRIP} {node_id, target_node, method, path}: the measured
 *       round trip of each <b>completed</b> call only. A failed call's time to failure is never
 *       recorded here.</li>
 *   <li>{@value MetricNames#RMI_ENDPOINT_EXPORTED} {node_id = that node}: 1 while the node's
 *       NodeEndpoint is exported, 0 otherwise, read from its RMI service at scrape time (reading
 *       never starts the service).</li>
 * </ul>
 *
 * <p>Re-creating this class against the same registry replaces each gauge instead of registering a
 * duplicate. Plain class, owned by {@link RmiModule}. Covered by RmiMetricsTest.</p>
 */
public class RmiMetrics {

    static final String TARGET_NODE = "target_node";
    static final String METHOD = "method";
    static final String PATH = "path";
    static final String OUTCOME = "outcome";
    static final String STAGE = "stage";
    static final String KIND = "kind";

    private final MeterRegistry registry;

    public RmiMetrics(MeterRegistry registry, Cluster cluster) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(cluster, "cluster must not be null");
        for (ClusterNode node : cluster.nodes()) {
            String tag = String.valueOf(node.id());
            registry.find(MetricNames.RMI_ENDPOINT_EXPORTED).tag(MetricNames.NODE_ID, tag).meters()
                    .forEach(registry::remove);
            Gauge.builder(MetricNames.RMI_ENDPOINT_EXPORTED, node, RmiMetrics::exported)
                    .description("1 while this node's NodeEndpoint is exported in its RMI registry, 0 otherwise")
                    .tag(MetricNames.NODE_ID, tag)
                    .register(registry);
        }
    }

    /** One finished call, successful or not. */
    public void record(RmiCallResult result) {
        Objects.requireNonNull(result, "result must not be null");
        String caller = String.valueOf(result.senderNodeId());
        String target = String.valueOf(result.targetNodeId());
        String method = result.method().name();
        registry.counter(MetricNames.RMI_CALLS_TOTAL, MetricNames.NODE_ID, caller, TARGET_NODE, target,
                METHOD, method, PATH, result.path().name(), OUTCOME, result.outcome().name()).increment();
        if (result.succeeded()) {
            Timer.builder(MetricNames.RMI_ROUND_TRIP)
                    .description("Measured round trip of a completed Experiment 1 call")
                    .tag(MetricNames.NODE_ID, caller)
                    .tag(TARGET_NODE, target)
                    .tag(METHOD, method)
                    .tag(PATH, result.path().name())
                    .register(registry)
                    .record(Duration.ofNanos(result.roundTripNanos()));
        } else {
            registry.counter(MetricNames.RMI_FAILURES_TOTAL, MetricNames.NODE_ID, caller, TARGET_NODE, target,
                    METHOD, method, STAGE, result.failureStage().name(), KIND, result.failureKind().name()).increment();
        }
    }

    /** 1 while the node's RMI service is running, 0 otherwise. Never starts it. */
    static double exported(ClusterNode node) {
        return RmiNodeService.find(node).map(service -> service.isRunning() ? 1d : 0d).orElse(0d);
    }
}
