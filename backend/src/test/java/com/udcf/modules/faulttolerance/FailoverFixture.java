package com.udcf.modules.faulttolerance;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.cluster.RoleProvider;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.modules.election.ElectionModule;
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
 * A standalone cluster with real 127.0.0.1 sockets, Experiment 4's {@link ElectionModule}, the
 * shared {@link RoleProvider} and a {@link FailoverCluster} on it, with short timings. Test
 * helper, not a test.
 *
 * <p>Test ports (Track B block, inside 26361-26395): cluster bases 26360 to 26390, so node k uses
 * election 26372 + k and replication 26378 + k (26373-26377, 26379-26383). Nothing else binds.</p>
 *
 * <p>Timings: heartbeat 100 ms, timeout 600 ms; Bully ok 400 ms, coordinator 1500 ms;
 * replication timeout 300 ms, simulated asynchronous delay 400 ms (or as given); catch-up bound
 * 250 ms. Worst-case failover = (600 + 100) + (400 + 1500) + (250 + 2 x 300) = 3450 ms; the
 * client retries 50 x 100 = 5000 ms.</p>
 */
final class FailoverFixture implements AutoCloseable {

    static final ClusterProperties.Ports PORTS = new ClusterProperties.Ports(26360, 26366, 26372, 26378, 26384, 26390);
    static final ElectionProperties ELECTION = new ElectionProperties(400, 1500, 150, 3000, 100, 600, 10000);
    static final ReplicationProperties REPLICATION = new ReplicationProperties(400, 300, 200);
    static final FaultToleranceProperties PROPERTIES = new FaultToleranceProperties(
            new FaultToleranceProperties.Client(50, 100), new FaultToleranceProperties.RoleQuerySettings(300, 3),
            new FaultToleranceProperties.Promotion(250), 10);
    static final Pattern ALL_CAPS_WORD = Pattern.compile("\\b[A-Z]{2,}\\b");

    final ClusterEventBus bus;
    final Cluster cluster;
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ElectionModule election;
    final RoleProvider roles;
    final FailoverCluster failover;

    FailoverFixture(int size) {
        this(size, FailoverCluster.NO_PROBE);
    }

    FailoverFixture(int size, FailoverCluster.PromotionProbe probe) {
        this(size, probe, REPLICATION.asyncDelayMillis());
    }

    FailoverFixture(int size, FailoverCluster.PromotionProbe probe, long asyncDelayMillis) {
        this(size, probe, asyncDelayMillis, PORTS);
    }

    /** With other port bases, for a test class with its own range (see its Javadoc). */
    FailoverFixture(int size, FailoverCluster.PromotionProbe probe, long asyncDelayMillis, ClusterProperties.Ports ports) {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), Clock.systemUTC());
        List<NodeCapacity> capacities = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            capacities.add(NodeCapacity.FAST);
        }
        cluster = new Cluster(new ClusterProperties(size, capacities, ports), bus);
        election = new ElectionModule(cluster, ELECTION, bus, registry);
        roles = new RoleProvider(cluster, election);
        ReplicationProperties replication = new ReplicationProperties(asyncDelayMillis, REPLICATION.timeoutMillis(),
                REPLICATION.batchSize());
        failover = new FailoverCluster(cluster, roles, PROPERTIES, ELECTION, replication, bus, registry, System::nanoTime,
                probe);
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

    /** The cluster's LEADER_CHANGED events naming {@code leaderId} as the new leader, in bus order. */
    List<ClusterEvent> leaderChangesTo(int leaderId) {
        return events("cluster", "LEADER_CHANGED").stream()
                .filter(e -> Integer.valueOf(leaderId).equals(e.data().get("leaderId"))).toList();
    }

    @Override
    public void close() {
        failover.close();
        roles.close();
        election.close();
        cluster.close();
        bus.close();
    }
}
