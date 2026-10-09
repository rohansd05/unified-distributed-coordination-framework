package com.udcf.modules.faulttolerance;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.modules.election.ElectionProperties;
import com.udcf.modules.replication.ReplicationNodeService;
import com.udcf.modules.replication.ReplicationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.core.ConditionFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A standalone cluster with real 127.0.0.1 sockets and a {@link FailoverCluster} on it, with
 * short timings. Test helper, not a test.
 *
 * <p>Test ports (Track B block, inside 26361-26395): cluster bases 26360 to 26390, so node k uses
 * election 26372 + k and replication 26378 + k (26373-26377, 26379-26383). Nothing else binds.</p>
 *
 * <p>Timings: heartbeat 100 ms, timeout 600 ms; replication timeout 300 ms, simulated
 * asynchronous delay 400 ms; catch-up bound 250 ms. Worst-case failover = (600 + 100) +
 * 2 x (250 + 2 x 300) = 2400 ms; the client retries 40 x 100 = 4000 ms.</p>
 */
final class FailoverFixture implements AutoCloseable {

    static final ClusterProperties.Ports PORTS = new ClusterProperties.Ports(26360, 26366, 26372, 26378, 26384, 26390);
    static final ElectionProperties ELECTION = new ElectionProperties(400, 1500, 150, 3000, 100, 600, 10000);
    static final ReplicationProperties REPLICATION = new ReplicationProperties(400, 300, 200);
    static final FaultToleranceProperties PROPERTIES = new FaultToleranceProperties(
            new FaultToleranceProperties.Client(40, 100), new FaultToleranceProperties.RoleQuerySettings(300, 3),
            new FaultToleranceProperties.Promotion(250), 10);
    static final Pattern ALL_CAPS_WORD = Pattern.compile("\\b[A-Z]{2,}\\b");

    final ClusterEventBus bus;
    final Cluster cluster;
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final FailoverCluster failover;

    FailoverFixture(int size) {
        this(size, FailoverCluster.NO_PROBE);
    }

    FailoverFixture(int size, FailoverCluster.PromotionProbe probe) {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), Clock.systemUTC());
        List<NodeCapacity> capacities = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            capacities.add(NodeCapacity.FAST);
        }
        cluster = new Cluster(new ClusterProperties(size, capacities, PORTS), bus);
        failover = new FailoverCluster(cluster, PROPERTIES, ELECTION, REPLICATION, bus, registry, System::nanoTime, probe);
    }

    static int replicationPort(int nodeId) {
        return PORTS.replicationBase() + nodeId;
    }

    static ConditionFactory within() {
        return await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(20));
    }

    ReplicationNodeService replication(int nodeId) {
        return ReplicationNodeService.find(cluster.node(nodeId)).orElseThrow();
    }

    FaultToleranceNodeService service(int nodeId) {
        return FaultToleranceNodeService.find(cluster.node(nodeId)).orElseThrow();
    }

    /** Starts Experiment 8 and waits until {@code primary} serves. */
    void startAndAwaitPrimary(int primary) {
        failover.start();
        awaitPrimary(primary);
    }

    void awaitPrimary(int primary) {
        within().until(() -> failover.currentPrimary().equals(java.util.Optional.of(primary)));
    }

    List<ClusterEvent> events(String type) {
        return bus.query(FaultToleranceNodeService.MODULE, null, 5000).stream().filter(e -> e.type().equals(type)).toList();
    }

    List<ClusterEvent> events(String module, String type) {
        return bus.query(module, null, 5000).stream().filter(e -> e.type().equals(type)).toList();
    }

    /** Every faulttolerance event so far: sentence case (no all-capitals word) and a node-clock Lamport time. */
    void assertEventsWellFormed() {
        List<ClusterEvent> all = bus.query(FaultToleranceNodeService.MODULE, null, 5000);
        assertThat(all).isNotEmpty().allSatisfy(event -> {
            assertThat(ALL_CAPS_WORD.matcher(Objects.requireNonNull(event.message())).find()).as(event.message()).isFalse();
            assertThat(event.lamportTime()).as(event.type()).isPositive();
        });
    }

    @Override
    public void close() {
        failover.close();
        cluster.close();
        bus.close();
    }
}
