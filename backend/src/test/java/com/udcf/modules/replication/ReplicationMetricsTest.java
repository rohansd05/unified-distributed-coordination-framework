package com.udcf.modules.replication;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.core.metrics.MetricNames;
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

/**
 * The Experiment 5 meters on a standalone three-node cluster.
 *
 * <p>Test-only port bases 28150 to 28650, so node k's replication port is 2845k: below Linux's
 * ephemeral range and Windows' dynamic range, apart from every other test class.</p>
 */
class ReplicationMetricsTest {

    private static final ClusterProperties CLUSTER = new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(28150, 28250, 28350, 28450, 28550, 28650));
    private static final ReplicationProperties PROPERTIES = new ReplicationProperties(50, 2000, 200);

    private ClusterEventBus bus;
    private Cluster cluster;
    private SimpleMeterRegistry registry;
    private ReplicationMetrics metrics;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), Clock.systemUTC());
        cluster = new Cluster(CLUSTER, bus);
        registry = new SimpleMeterRegistry();
        metrics = new ReplicationMetrics(registry, cluster);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    private double gauge(String name, int nodeId) {
        return registry.get(name).tag(MetricNames.NODE_ID, String.valueOf(nodeId)).gauge().value();
    }

    @Test
    @DisplayName("every replication meter carries node_id (R5)")
    void everyMeterHasNodeId() {
        metrics.recordWrite(1, ConsistencyModel.SYNCHRONOUS);
        metrics.recordPush(PushOutcome.acked(2, ApplyResult.APPLIED, 1, 0.5), "SYNCHRONOUS");
        metrics.recordPush(PushOutcome.withoutReply(3, PushStatus.FAILED, "refused"), "SYNCHRONOUS");

        List<Meter> meters = registry.getMeters();

        assertThat(meters).isNotEmpty().allSatisfy(meter -> assertThat(meter.getId().getTag(MetricNames.NODE_ID))
                .as(meter.getId().getName()).isNotNull());
        assertThat(meters).extracting(meter -> meter.getId().getName()).contains(
                MetricNames.REPLICATION_WRITES_TOTAL, MetricNames.REPLICATION_ACKS_TOTAL,
                MetricNames.REPLICATION_LATENCY, MetricNames.REPLICATION_FAILURES_TOTAL,
                MetricNames.REPLICATION_STORE_ITEMS, MetricNames.REPLICATION_EPOCH);
    }

    @Test
    @DisplayName("acks by result, latency by kind, failures, writes by model; NOT_SENT and ABANDONED count nowhere")
    void pushesAndWritesAreCounted() {
        metrics.recordWrite(1, ConsistencyModel.ASYNCHRONOUS);
        metrics.recordPush(PushOutcome.acked(2, ApplyResult.STALE, 1, 2.0), ReplicationMetrics.OUT_OF_ORDER);
        metrics.recordPush(PushOutcome.acked(2, ApplyResult.APPLIED, 1, 4.0), "ASYNCHRONOUS");
        metrics.recordPush(PushOutcome.withoutReply(3, PushStatus.FAILED, "refused"), "ASYNCHRONOUS");
        metrics.recordPush(PushOutcome.withoutReply(3, PushStatus.NOT_SENT, "down"), "ASYNCHRONOUS");
        metrics.recordPush(PushOutcome.withoutReply(3, PushStatus.ABANDONED, "down"), "ASYNCHRONOUS");

        assertThat(registry.get(MetricNames.REPLICATION_WRITES_TOTAL).tags(MetricNames.NODE_ID, "1",
                ReplicationMetrics.MODEL, "ASYNCHRONOUS").counter().count()).isEqualTo(1);
        assertThat(registry.get(MetricNames.REPLICATION_ACKS_TOTAL).tags(MetricNames.NODE_ID, "2",
                ReplicationMetrics.RESULT, "STALE").counter().count()).isEqualTo(1);
        assertThat(registry.get(MetricNames.REPLICATION_ACKS_TOTAL).tags(MetricNames.NODE_ID, "2",
                ReplicationMetrics.RESULT, "APPLIED").counter().count()).isEqualTo(1);
        assertThat(registry.get(MetricNames.REPLICATION_LATENCY).tags(MetricNames.NODE_ID, "2",
                ReplicationMetrics.KIND, "ASYNCHRONOUS").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(4.0);
        assertThat(registry.get(MetricNames.REPLICATION_LATENCY).tags(MetricNames.NODE_ID, "2",
                ReplicationMetrics.KIND, ReplicationMetrics.OUT_OF_ORDER).timer().count()).isEqualTo(1);
        assertThat(registry.get(MetricNames.REPLICATION_FAILURES_TOTAL).tag(MetricNames.NODE_ID, "3")
                .counter().count()).isEqualTo(1);
        assertThat(registry.find(MetricNames.REPLICATION_ACKS_TOTAL).tag(MetricNames.NODE_ID, "3").counters())
                .isEmpty();
    }

    @Test
    @DisplayName("store-item and epoch gauges are NaN until the service runs, never start it, follow it, and are NaN again after a crash")
    void gaugesAreNaNUntilRunningAndNeverStart() {
        assertThat(gauge(MetricNames.REPLICATION_STORE_ITEMS, 1)).isNaN();
        assertThat(gauge(MetricNames.REPLICATION_EPOCH, 1)).isNaN();
        assertThat(cluster.node(1).service(ReplicationNodeService.NAME)).isEmpty();

        ReplicationNodeService service = ReplicationNodeService.on(cluster.node(1), cluster, PROPERTIES, bus);
        service.becomePrimary(2);
        service.write("a", "1", ConsistencyModel.SYNCHRONOUS, List.of());
        service.write("b", "2", ConsistencyModel.SYNCHRONOUS, List.of());

        assertThat(gauge(MetricNames.REPLICATION_STORE_ITEMS, 1)).isEqualTo(2.0);
        assertThat(gauge(MetricNames.REPLICATION_EPOCH, 1)).isEqualTo(2.0);
        assertThat(gauge(MetricNames.REPLICATION_STORE_ITEMS, 2)).isNaN();

        cluster.crash(1);
        assertThat(gauge(MetricNames.REPLICATION_STORE_ITEMS, 1)).isNaN();
        assertThat(gauge(MetricNames.REPLICATION_EPOCH, 1)).isNaN();
    }

    @Test
    @DisplayName("re-creating the metrics on the same registry replaces each gauge instead of duplicating it")
    void recreatingReplacesGauges() {
        new ReplicationMetrics(registry, cluster);

        assertThat(registry.find(MetricNames.REPLICATION_STORE_ITEMS).gauges()).hasSize(3);
        assertThat(registry.find(MetricNames.REPLICATION_EPOCH).gauges()).hasSize(3);
    }
}
