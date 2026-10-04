package com.udcf.core.metrics;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.events.ClusterEventBus;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Cluster-wide meters, all bound to live objects so Prometheus reads current state at
 * scrape time and nothing is cached (docs/HANDOFF.md 6.9).
 *
 * <ul>
 *   <li>{@code distributed_node_status{node_id=k}}: 1 while node k is up, 0 while crashed.</li>
 *   <li>{@code distributed_events_published_total{node_id="0"}}: every event published.</li>
 *   <li>{@code distributed_event_notifications_dropped_total{node_id="0"}}: events subscribers
 *       missed because the dispatch queue was full.</li>
 * </ul>
 */
@Component
public class ClusterMetrics {

    public ClusterMetrics(Cluster cluster, ClusterEventBus bus, MeterRegistry registry) {
        for (ClusterNode node : cluster.nodes()) {
            Gauge.builder(MetricNames.NODE_STATUS, node, n -> n.isUp() ? 1 : 0)
                    .description("1 while the node is up, 0 while it is crashed")
                    .tag(MetricNames.NODE_ID, String.valueOf(node.id()))
                    .register(registry);
        }
        FunctionCounter.builder(MetricNames.EVENTS_PUBLISHED_TOTAL, bus, ClusterEventBus::publishedCount)
                .description("Cluster events published since startup")
                .tag(MetricNames.NODE_ID, MetricNames.CLUSTER_NODE_ID)
                .register(registry);
        FunctionCounter.builder(MetricNames.EVENT_NOTIFICATIONS_DROPPED_TOTAL, bus,
                        ClusterEventBus::droppedNotifications)
                .description("Events live subscribers missed because the dispatch queue was full")
                .tag(MetricNames.NODE_ID, MetricNames.CLUSTER_NODE_ID)
                .register(registry);
    }
}
