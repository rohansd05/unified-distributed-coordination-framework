package com.udcf.core.metrics;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.core.events.EventProperties;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the cluster meters: one status gauge per node bound to the live node, event
 * counters bound to the live bus, and node_id on every meter (R5).
 */
class ClusterMetricsTest {

    private static final ClusterProperties PROPERTIES = new ClusterProperties(5,
            List.of(FAST, MEDIUM, SLOW, MEDIUM, FAST),
            new ClusterProperties.Ports(1100, 6000, 7000, 7100, 7200, 7300));

    private SimpleMeterRegistry registry;
    private ClusterEventBus bus;
    private Cluster cluster;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        bus = new ClusterEventBus(new EventProperties(100, 100), Clock.systemUTC());
        cluster = new Cluster(PROPERTIES, bus);
        new ClusterMetrics(cluster, bus, registry);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    @Test
    @DisplayName("one distributed_node_status gauge per node, tagged with its node_id")
    void oneStatusGaugePerNode() {
        assertThat(registry.find(MetricNames.NODE_STATUS).gauges()).hasSize(5);
        for (int id = 1; id <= 5; id++) {
            assertThat(registry.get(MetricNames.NODE_STATUS).tag(MetricNames.NODE_ID, String.valueOf(id))
                    .gauge().value()).isEqualTo(1.0d);
        }
    }

    @Test
    @DisplayName("the status gauge reads the live node: 1 -> 0 on crash -> 1 on recovery, same meter")
    void statusGaugeIsLive() {
        Gauge gauge = registry.get(MetricNames.NODE_STATUS).tag(MetricNames.NODE_ID, "2").gauge();
        assertThat(gauge.value()).isEqualTo(1.0d);

        cluster.crash(2);
        assertThat(gauge.value()).isZero();
        assertThat(registry.get(MetricNames.NODE_STATUS).tag(MetricNames.NODE_ID, "3").gauge().value())
                .isEqualTo(1.0d);

        cluster.recover(2);
        assertThat(gauge.value()).isEqualTo(1.0d);
        assertThat(registry.get(MetricNames.NODE_STATUS).tag(MetricNames.NODE_ID, "2").gauge()).isSameAs(gauge);
        assertThat(registry.find(MetricNames.NODE_STATUS).gauges()).hasSize(5);
    }

    @Test
    @DisplayName("the published counter tracks the bus")
    void publishedCounterTracksBus() {
        FunctionCounter published = registry.get(MetricNames.EVENTS_PUBLISHED_TOTAL)
                .tag(MetricNames.NODE_ID, "0").functionCounter();
        double before = published.count();
        assertThat(before).isEqualTo(bus.publishedCount());   // CLUSTER_STARTED

        bus.publish(EventDraft.of("m", 1, "A", 1));
        bus.publish(EventDraft.of("m", 1, "B", 2));
        cluster.crash(1);

        assertThat(published.count()).isEqualTo(before + 3);
        assertThat(published.count()).isEqualTo(bus.publishedCount());
    }

    @Test
    @DisplayName("the dropped counter tracks droppedNotifications")
    void droppedCounterTracksBus() {
        FunctionCounter dropped = registry.get(MetricNames.EVENT_NOTIFICATIONS_DROPPED_TOTAL)
                .tag(MetricNames.NODE_ID, "0").functionCounter();
        assertThat(dropped.count()).isZero();

        bus.close();   // after close every publish counts as a dropped notification
        bus.publish(EventDraft.of("m", 1, "A", 1));
        bus.publish(EventDraft.of("m", 1, "B", 2));

        assertThat(dropped.count()).isEqualTo(2.0d);
        assertThat(dropped.count()).isEqualTo(bus.droppedNotifications());
    }

    @Test
    @DisplayName("every registered meter has a node_id tag")
    void everyMeterHasNodeId() {
        List<Meter> meters = registry.getMeters();

        assertThat(meters).hasSize(7).allSatisfy(meter ->
                assertThat(meter.getId().getTag(MetricNames.NODE_ID)).as(meter.getId().getName()).isNotBlank());
    }
}
