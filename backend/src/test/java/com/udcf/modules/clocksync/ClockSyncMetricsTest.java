package com.udcf.modules.clocksync;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.core.metrics.MetricNames;
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

class ClockSyncMetricsTest {

    private static final ClusterProperties CLUSTER_PROPS = new ClusterProperties(
            3,
            List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(24100, 24200, 24300, 24400, 24500, 24600)
    );

    private SimpleMeterRegistry registry;
    private ClusterEventBus bus;
    private Cluster cluster;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        bus = new ClusterEventBus(new EventProperties(100, 100), Clock.systemUTC());
        cluster = new Cluster(CLUSTER_PROPS, bus);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    @Test
    @DisplayName("binds distributed_clock_value per node with node_id tag")
    void bindsClockGauges() {
        new ClockSyncMetrics(cluster, registry);

        assertThat(registry.find(MetricNames.CLOCK_VALUE).gauges()).hasSize(3);
        for (int id = 1; id <= 3; id++) {
            assertThat(registry.get(MetricNames.CLOCK_VALUE)
                    .tag(MetricNames.NODE_ID, String.valueOf(id))
                    .gauge().value()).isEqualTo(0.0d);
        }

        cluster.node(2).clock().tick();
        cluster.node(2).clock().tick();

        assertThat(registry.get(MetricNames.CLOCK_VALUE)
                .tag(MetricNames.NODE_ID, "2")
                .gauge().value()).isEqualTo(2.0d);
    }

    @Test
    @DisplayName("binding is idempotent and does not create duplicate meters")
    void idempotentBinding() {
        new ClockSyncMetrics(cluster, registry);
        new ClockSyncMetrics(cluster, registry);

        assertThat(registry.find(MetricNames.CLOCK_VALUE).gauges()).hasSize(3);
    }
}
