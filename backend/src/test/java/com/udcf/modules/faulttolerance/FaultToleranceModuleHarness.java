package com.udcf.modules.faulttolerance;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.modules.election.ElectionProperties;
import com.udcf.modules.replication.ReplicationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.core.ConditionFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.awaitility.Awaitility.await;

/**
 * A standalone cluster with real 127.0.0.1 sockets and a {@link FaultToleranceModule} on it,
 * without Spring, with short timings. Test helper, not a test.
 *
 * <p>Test ports (Track B block, own range 26460-26495): cluster bases 26460 to 26490, so node k
 * binds election 26472 + k and replication 26478 + k (26473-26477, 26479-26483). The RMI, clock,
 * requests and MapReduce services never start here.</p>
 *
 * <p>Timings as {@link FailoverFixture}: heartbeat 100 ms, timeout 600 ms; replication timeout
 * 300 ms; catch-up 250 ms; the client retries 40 x 100 ms. The simulated asynchronous delay is a
 * parameter (the async-loss test makes it long, so the loss window cannot be missed).</p>
 */
final class FaultToleranceModuleHarness implements AutoCloseable {

    static final ClusterProperties.Ports PORTS = new ClusterProperties.Ports(26460, 26466, 26472, 26478, 26484, 26490);
    static final ElectionProperties ELECTION = FailoverFixture.ELECTION;
    static final int MAX_RATE = 200;

    final ClusterEventBus bus;
    final Cluster cluster;
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final FailoverCluster failover;
    final FaultToleranceModule module;
    final ReplicationProperties replication;

    FaultToleranceModuleHarness(int size) {
        this(size, 10, 400, FailoverFixture.REPLICATION.asyncDelayMillis());
    }

    FaultToleranceModuleHarness(int size, int historyLimit, int maxUpdates, long asyncDelayMillis) {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), Clock.systemUTC());
        List<NodeCapacity> capacities = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            capacities.add(NodeCapacity.FAST);
        }
        cluster = new Cluster(new ClusterProperties(size, capacities, PORTS), bus);
        replication = new ReplicationProperties(asyncDelayMillis, FailoverFixture.REPLICATION.timeoutMillis(),
                FailoverFixture.REPLICATION.batchSize());
        FaultToleranceProperties properties = new FaultToleranceProperties(FailoverFixture.PROPERTIES.client(),
                FailoverFixture.PROPERTIES.roleQuery(), FailoverFixture.PROPERTIES.promotion(), historyLimit);
        FaultToleranceModuleProperties moduleProperties = new FaultToleranceModuleProperties(
                new FaultToleranceModuleProperties.Stream(4, MAX_RATE, maxUpdates));
        failover = new FailoverCluster(cluster, properties, ELECTION, replication, bus, registry, System::nanoTime,
                FailoverCluster.NO_PROBE);
        module = new FaultToleranceModule(cluster, bus, properties, moduleProperties, ELECTION, replication, registry,
                Clock.systemUTC(), failover);
    }

    static ConditionFactory within() {
        return await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(20));
    }

    void awaitPrimary(int primary) {
        within().until(() -> failover.currentPrimary().equals(java.util.Optional.of(primary)));
    }

    List<ClusterEvent> events(String type) {
        return bus.query(FaultToleranceModule.ID, null, 5000).stream().filter(e -> e.type().equals(type)).toList();
    }

    @Override
    public void close() {
        module.close();
        cluster.close();
        bus.close();
    }
}
