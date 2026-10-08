package com.udcf.modules.clocksync;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.metrics.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Binds Micrometer metrics for Experiment 3 (Clock Synchronization).
 *
 * <p>Exposes {@code distributed_clock_value{node_id="k"}} reading the node's Lamport clock value.
 * Registration is idempotent so creating multiple instances or re-registering against the same
 * registry does not throw or create duplicate meters.</p>
 */
@Component
public class ClockSyncMetrics {

    public ClockSyncMetrics(Cluster cluster, MeterRegistry registry) {
        Objects.requireNonNull(cluster, "cluster must not be null");
        Objects.requireNonNull(registry, "registry must not be null");

        for (ClusterNode node : cluster.nodes()) {
            String nodeIdTag = String.valueOf(node.id());
            if (registry.find(MetricNames.CLOCK_VALUE).tag(MetricNames.NODE_ID, nodeIdTag).gauge() == null) {
                Gauge.builder(MetricNames.CLOCK_VALUE, node, n -> n.clock().current())
                        .description("Lamport clock value of the node")
                        .tag(MetricNames.NODE_ID, nodeIdTag)
                        .register(registry);
            }
        }
    }
}
