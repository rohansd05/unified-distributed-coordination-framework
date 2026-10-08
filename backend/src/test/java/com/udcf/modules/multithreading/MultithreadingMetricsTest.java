package com.udcf.modules.multithreading;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.core.metrics.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The Exp 2 gauges: bound once per node, following the node's current executor across crash
 * and recovery, NaN while there is no executor. Includes the two gauge tests that moved here
 * from ThreadPoolMetricsTest with the gauges (E2c).
 *
 * <p>Standalone three-node cluster; test-only port bases 21200 to 21250 (node k's requests
 * port is 21240 + k), below 32768, outside the Linux and Windows ephemeral ranges.</p>
 */
class MultithreadingMetricsTest {

    private static final ClusterProperties CLUSTER = new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(21200, 21210, 21220, 21230, 21240, 21250));
    private static final MultithreadingProperties PROPERTIES = new MultithreadingProperties(200, 60,
            "udcf-worker-", 30, 500, 2000,
            new MultithreadingProperties.Backpressure(50, WorkloadType.CPU_HASH, 200));
    private static final List<String> GAUGES = List.of(MetricNames.ACTIVE_THREADS, MetricNames.POOL_SIZE,
            MetricNames.QUEUED_REQUESTS, MetricNames.QUEUE_REMAINING_CAPACITY, MetricNames.REQUEST_THROUGHPUT,
            MetricNames.RESPONSE_TIME_P95_MILLIS);

    private ClusterEventBus bus;
    private Cluster cluster;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(1000, 1000), Clock.systemUTC());
        cluster = new Cluster(CLUSTER, bus);
        registry = new SimpleMeterRegistry();
        new MultithreadingMetrics(cluster, registry);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    private double gauge(String name, int nodeId) {
        return registry.get(name).tag(MetricNames.NODE_ID, String.valueOf(nodeId)).gauge().value();
    }

    private RequestsNodeService start(int nodeId) {
        return RequestsNodeService.on(cluster.node(nodeId), PROPERTIES, registry, bus);
    }

    private long gaugeCount() {
        return registry.getMeters().stream().filter(meter -> meter.getId().getType() == Meter.Type.GAUGE).count();
    }

    @Test
    @DisplayName("registers the six distributed_* gauges the Grafana dashboards expect, once for every node")
    void registersExpectedGauges() {
        for (String name : GAUGES) {
            assertThat(registry.find(name).gauges()).as(name).hasSize(3)
                    .extracting(gauge -> gauge.getId().getTag(MetricNames.NODE_ID))
                    .containsExactlyInAnyOrder("1", "2", "3");
        }
        assertThat(gaugeCount()).isEqualTo(18);
    }

    @Test
    @DisplayName("before first use every gauge is NaN, and reading one never starts a service")
    void nanBeforeFirstUse() {
        for (String name : GAUGES) {
            assertThat(gauge(name, 1)).as(name).isNaN();
        }
        assertThat(RequestsNodeService.find(cluster.node(1))).isEmpty();
    }

    @Test
    @DisplayName("gauges read live executor state rather than a cached copy")
    void gaugesTrackLiveExecutorState() {
        RequestsNodeService service = start(1);
        assertThat(gauge(MetricNames.QUEUED_REQUESTS, 1)).isZero();
        assertThat(gauge(MetricNames.QUEUE_REMAINING_CAPACITY, 1)).isEqualTo(200.0);

        service.processing().submitTogether(WorkloadType.IO_SIMULATED, 5000, 6);   // 4 run for 2 s, 2 wait
        await().atMost(5, TimeUnit.SECONDS).until(() -> gauge(MetricNames.ACTIVE_THREADS, 1) == 4.0);

        assertThat(gauge(MetricNames.QUEUED_REQUESTS, 1)).isEqualTo(2.0);
        assertThat(gauge(MetricNames.QUEUE_REMAINING_CAPACITY, 1)).isEqualTo(198.0);
        assertThat(gauge(MetricNames.POOL_SIZE, 1)).isEqualTo(4.0);
        assertThat(gauge(MetricNames.ACTIVE_THREADS, 2)).as("node 2 is untouched").isNaN();
    }

    @Test
    @DisplayName("a crashed node's gauges are NaN, and after recovery they read the new executor")
    void followTheExecutorAcrossCrashAndRecovery() {
        RequestsNodeService service = start(1);
        service.processing().submitTogether(WorkloadType.IO_SIMULATED, 5000, 6);
        await().atMost(5, TimeUnit.SECONDS).until(() -> gauge(MetricNames.ACTIVE_THREADS, 1) == 4.0);
        long gaugesBefore = gaugeCount();

        cluster.crash(1);
        for (String name : GAUGES) {
            assertThat(gauge(name, 1)).as(name + " while crashed").isNaN();
        }

        cluster.recover(1);
        assertThat(gauge(MetricNames.ACTIVE_THREADS, 1)).isZero();
        assertThat(gauge(MetricNames.QUEUED_REQUESTS, 1)).isZero();
        assertThat(gauge(MetricNames.QUEUE_REMAINING_CAPACITY, 1)).isEqualTo(200.0);
        service.processing().submitTogether(WorkloadType.IO_SIMULATED, 5000, 2);
        await().atMost(5, TimeUnit.SECONDS).until(() -> gauge(MetricNames.ACTIVE_THREADS, 1) == 2.0);
        assertThat(gaugeCount()).as("nothing is registered again").isEqualTo(gaugesBefore);
    }

    @Test
    @DisplayName("every Exp 2 gauge carries its own node's node_id")
    void everyGaugeCarriesItsNodeId() {
        start(1);
        start(3);

        assertThat(registry.getMeters()).filteredOn(meter -> meter.getId().getType() == Meter.Type.GAUGE)
                .allSatisfy(meter -> assertThat(meter.getId().getTag(MetricNames.NODE_ID)).isIn("1", "2", "3"));
        assertThat(gauge(MetricNames.POOL_SIZE, 3)).isZero();
        Gauge slowCapacity = registry.get(MetricNames.QUEUE_REMAINING_CAPACITY).tag(MetricNames.NODE_ID, "3").gauge();
        assertThat(slowCapacity.value()).isEqualTo(200.0);
    }
}
