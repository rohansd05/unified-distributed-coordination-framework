package com.udcf.modules.replication;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.metrics.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/**
 * Experiment 5 meters, each tagged with {@code node_id} (R5):
 * <ul>
 *   <li>{@value MetricNames#REPLICATION_LATENCY} {node_id = backup, kind SYNCHRONOUS |
 *       ASYNCHRONOUS | OUT_OF_ORDER}: the measured TCP round trip of each acknowledged push
 *       (never the simulated delay).</li>
 *   <li>{@value MetricNames#REPLICATION_ACKS_TOTAL} {node_id = backup, result}: acknowledgements
 *       by what the backup's store did.</li>
 *   <li>{@value MetricNames#REPLICATION_FAILURES_TOTAL} {node_id = backup}: pushes that got no
 *       reply (FAILED). Pushes the sender never sent or abandoned because it went down are not
 *       backup failures and are not counted.</li>
 *   <li>{@value MetricNames#REPLICATION_WRITES_TOTAL} {node_id = primary, model}: client writes.</li>
 *   <li>{@value MetricNames#REPLICATION_STORE_ITEMS} and {@value MetricNames#REPLICATION_EPOCH}
 *       {node_id}: read from the node's replication service at scrape time. NaN ("no value",
 *       never an invented 0) while it is not running, and reading never starts it.</li>
 * </ul>
 *
 * <p>Re-creating this class against the same registry replaces each gauge instead of
 * registering a duplicate. Anti-entropy and catch-up are not counted here (they are reported by
 * their own results and events). Plain class, owned by {@link ReplicationModule}. Covered by
 * ReplicationMetricsTest.</p>
 */
public class ReplicationMetrics {

    static final String KIND = "kind";
    static final String RESULT = "result";
    static final String MODEL = "model";
    static final String OUT_OF_ORDER = "OUT_OF_ORDER";

    private final MeterRegistry registry;

    public ReplicationMetrics(MeterRegistry registry, Cluster cluster) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(cluster, "cluster must not be null");
        for (ClusterNode node : cluster.nodes()) {
            bind(node, MetricNames.REPLICATION_STORE_ITEMS, "Items in this node's replicated store",
                    service -> service.snapshot().size());
            bind(node, MetricNames.REPLICATION_EPOCH, "This node's replication store epoch",
                    ReplicationNodeService::epoch);
        }
    }

    /** One client write accepted by {@code primaryId}. */
    public void recordWrite(int primaryId, ConsistencyModel model) {
        registry.counter(MetricNames.REPLICATION_WRITES_TOTAL, MetricNames.NODE_ID, String.valueOf(primaryId),
                MODEL, model.name()).increment();
    }

    /**
     * One push outcome.
     *
     * @param kind the model name, or {@link #OUT_OF_ORDER} for an injected stale update
     */
    public void recordPush(PushOutcome outcome, String kind) {
        String nodeId = String.valueOf(outcome.backupId());
        switch (outcome.status()) {
            case ACKED -> {
                registry.counter(MetricNames.REPLICATION_ACKS_TOTAL, MetricNames.NODE_ID, nodeId,
                        RESULT, outcome.result().orElseThrow().name()).increment();
                Timer.builder(MetricNames.REPLICATION_LATENCY)
                        .description("Measured TCP round trip of an acknowledged replication push")
                        .tag(MetricNames.NODE_ID, nodeId)
                        .tag(KIND, kind)
                        .register(registry)
                        .record(Duration.ofNanos(Math.round(outcome.latencyMillis().orElseThrow() * 1_000_000d)));
            }
            case FAILED -> registry.counter(MetricNames.REPLICATION_FAILURES_TOTAL, MetricNames.NODE_ID, nodeId)
                    .increment();
            case NOT_SENT, ABANDONED -> {
                // The sender went down: not a backup failure, not a measurement.
            }
        }
    }

    /** The node's current value, or NaN while its replication service is not running. Never starts it. */
    static double read(ClusterNode node, ToDoubleFunction<ReplicationNodeService> reading) {
        return ReplicationNodeService.find(node)
                .filter(ReplicationNodeService::isRunning)
                .map(service -> {
                    try {
                        return reading.applyAsDouble(service);
                    } catch (NodeDownException e) {
                        return Double.NaN;   // crashed between the check and the read
                    }
                })
                .orElse(Double.NaN);
    }

    private void bind(ClusterNode node, String name, String description,
                      ToDoubleFunction<ReplicationNodeService> reading) {
        String tag = String.valueOf(node.id());
        registry.find(name).tag(MetricNames.NODE_ID, tag).meters().forEach(registry::remove);
        Gauge.builder(name, node, n -> read(n, reading))
                .description(description)
                .tag(MetricNames.NODE_ID, tag)
                .register(registry);
    }
}
