package com.udcf.modules.loadbalancing;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.core.metrics.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The Experiment 6 meters against a SimpleMeterRegistry. The cluster is only a list of nodes
 * here: no service is started and no socket is opened (port bases 47140 to 47640, inside the
 * Exp 6 test range, are never bound).
 */
class LoadBalancingMetricsTest {

    private static final ClusterProperties CLUSTER = new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(47140, 47240, 47340, 47440, 47540, 47640));

    private ClusterEventBus bus;
    private Cluster cluster;
    private SimpleMeterRegistry registry;
    private final AtomicReference<List<WorkerInfo>> current = new AtomicReference<>(List.of());

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(100, 100), java.time.Clock.systemUTC());
        cluster = new Cluster(CLUSTER, bus);
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    private static List<WorkerInfo> workers() {
        return List.of(new WorkerInfo(1, 47541, "FAST", 4), new WorkerInfo(2, 47542, "MEDIUM", 2),
                new WorkerInfo(3, 47543, "SLOW", 1));
    }

    private double dispatches(int nodeId, Strategy strategy, String outcome) {
        return registry.get(MetricNames.BALANCER_DISPATCHES_TOTAL)
                .tag(MetricNames.NODE_ID, String.valueOf(nodeId))
                .tag(LoadBalancingMetrics.STRATEGY, strategy.name())
                .tag(LoadBalancingMetrics.OUTCOME, outcome)
                .counter().count();
    }

    private double inFlightGauge(int nodeId) {
        return registry.get(MetricNames.BALANCER_IN_FLIGHT).tag(MetricNames.NODE_ID, String.valueOf(nodeId))
                .gauge().value();
    }

    @Test
    @DisplayName("each run adds every worker's served, failed and declined counts, and runs accumulate")
    void countersFromWorkers() {
        LoadBalancingMetrics metrics = new LoadBalancingMetrics(registry, cluster, current::get);
        List<WorkerInfo> run = workers();
        WorkerInfo w1 = run.get(0);
        WorkerInfo w3 = run.get(2);
        for (int i = 0; i < 5; i++) {
            w1.onDispatch();
            w1.onComplete(1d);
        }
        w3.onDispatch();
        w3.onFailure();
        w3.onDispatch();
        w3.onDeclined();

        metrics.recordRun(Strategy.ROUND_ROBIN, run, 12.5d);
        metrics.recordRun(Strategy.ROUND_ROBIN, run, 10d);

        assertThat(dispatches(1, Strategy.ROUND_ROBIN, "served")).isEqualTo(10d);
        assertThat(dispatches(3, Strategy.ROUND_ROBIN, "failed")).isEqualTo(2d);
        assertThat(dispatches(3, Strategy.ROUND_ROBIN, "declined")).isEqualTo(2d);
        assertThat(dispatches(2, Strategy.ROUND_ROBIN, "served")).isZero();
    }

    @Test
    @DisplayName("a finished run records its makespan at node_id 0 with its strategy; a failed run records none")
    void makespanTimer() {
        LoadBalancingMetrics metrics = new LoadBalancingMetrics(registry, cluster, current::get);

        metrics.recordRun(Strategy.LEAST_CONNECTIONS, workers(), 250d);
        metrics.recordRun(Strategy.LEAST_CONNECTIONS, workers(), null);

        Timer timer = registry.get(MetricNames.BALANCER_MAKESPAN)
                .tag(MetricNames.NODE_ID, MetricNames.CLUSTER_NODE_ID)
                .tag(LoadBalancingMetrics.STRATEGY, Strategy.LEAST_CONNECTIONS.name()).timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(250d);
    }

    @Test
    @DisplayName("the in-flight gauge reads the live worker: 0 before any run, the count during, 0 after; never NaN")
    void inFlightGaugeIsLive() {
        new LoadBalancingMetrics(registry, cluster, current::get);
        for (int id = 1; id <= 3; id++) {
            assertThat(inFlightGauge(id)).isZero();   // no run yet: the list is empty
        }

        List<WorkerInfo> run = workers();
        current.set(run);
        run.get(1).onDispatch();
        run.get(1).onDispatch();
        assertThat(inFlightGauge(2)).isEqualTo(2d);

        run.get(1).onComplete(1d);
        run.get(1).onFailure();
        assertThat(inFlightGauge(2)).isZero();
        assertThat(registry.find(MetricNames.BALANCER_IN_FLIGHT).gauges())
                .extracting(Gauge::value).noneMatch(v -> v.isNaN());
    }

    @Test
    @DisplayName("creating the metrics twice on one registry neither throws nor duplicates, and the gauge follows the newest")
    void reRegistrationIsSafe() {
        AtomicReference<List<WorkerInfo>> older = new AtomicReference<>(workers());
        new LoadBalancingMetrics(registry, cluster, older::get);
        older.get().get(0).onDispatch();

        List<WorkerInfo> newer = workers();
        assertThatCode(() -> new LoadBalancingMetrics(registry, cluster, () -> newer)).doesNotThrowAnyException();

        assertThat(registry.find(MetricNames.BALANCER_IN_FLIGHT).gauges()).hasSize(3);
        assertThat(inFlightGauge(1)).isZero();          // reads the newer, idle workers
        newer.get(0).onDispatch();
        assertThat(inFlightGauge(1)).isEqualTo(1d);
    }

    @Test
    @DisplayName("every meter carries node_id; the cluster-level makespan uses node_id 0 (R5)")
    void everyMeterHasNodeId() {
        LoadBalancingMetrics metrics = new LoadBalancingMetrics(registry, cluster, current::get);
        metrics.recordRun(Strategy.WEIGHTED_ROUND_ROBIN, workers(), 5d);

        List<Meter> meters = registry.getMeters();
        assertThat(meters).isNotEmpty().allSatisfy(m ->
                assertThat(m.getId().getTag(MetricNames.NODE_ID)).as(m.getId().getName()).isNotNull());
        assertThat(registry.find(MetricNames.BALANCER_MAKESPAN).meters())
                .allSatisfy(m -> assertThat(m.getId().getTag(MetricNames.NODE_ID)).isEqualTo("0"));
    }
}
