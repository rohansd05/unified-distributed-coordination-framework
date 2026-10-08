package com.udcf.modules.loadbalancing;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.modules.multithreading.MultithreadingProperties;
import com.udcf.modules.multithreading.RequestsNodeService;
import com.udcf.modules.multithreading.WorkloadType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The balancer over real TCP into each node's Exp 2 requests service, on a standalone
 * three-node cluster (FAST, MEDIUM, SLOW). No Spring context, so crashes here never disturb
 * other tests. No sleeps: a crash mid-run waits for the node's executor to report work in
 * flight and queued.
 *
 * <p>Test-only port bases 21300 to 21350 (requests ports 21341 to 21343), below 32768, outside
 * the Linux and Windows ephemeral ranges, and apart from every other test class.</p>
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class LoadBalancingGatewayTest {

    private static final ClusterProperties CLUSTER = new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(21300, 21310, 21320, 21330, 21340, 21350));
    private static final MultithreadingProperties MULTITHREADING =
            new MultithreadingProperties(200, 60, "udcf-worker-", 30, 500, 2000,
                    new MultithreadingProperties.Backpressure(50, WorkloadType.CPU_HASH, 200));
    private static final LoadBalancingProperties PROPERTIES = new LoadBalancingProperties(10_000,
            new LoadBalancingProperties.Defaults(60, 400, 12),
            new LoadBalancingProperties.Limits(1000, 5000, 50, 500_000));

    private ClusterEventBus bus;
    private Cluster cluster;
    private LoadBalancingGateway gateway;
    private ExecutorService testThreads;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), java.time.Clock.systemUTC());
        cluster = new Cluster(CLUSTER, bus);
        gateway = new LoadBalancingGateway(cluster, MULTITHREADING, new SimpleMeterRegistry(), bus, PROPERTIES);
    }

    @AfterEach
    void tearDown() {
        if (testThreads != null) {
            testThreads.shutdownNow();
        }
        cluster.close();
        bus.close();
    }

    private WorkerInfo worker(int nodeId) {
        return gateway.workers().stream().filter(w -> w.nodeId() == nodeId).findFirst().orElseThrow();
    }

    private List<ClusterEvent> events(String module, String type) {
        return bus.query(module, null, 5000).stream().filter(e -> e.type().equals(type)).toList();
    }

    @Test
    @DisplayName("before any run, every cluster node is a worker on its requests port, weighted 4 : 2 : 1")
    void workersFromCluster() {
        assertThat(gateway.workers()).extracting(WorkerInfo::nodeId).containsExactly(1, 2, 3);
        assertThat(gateway.workers()).extracting(WorkerInfo::port).containsExactly(21341, 21342, 21343);
        assertThat(gateway.workers()).extracting(WorkerInfo::label).containsExactly("FAST", "MEDIUM", "SLOW");
        assertThat(gateway.workers()).extracting(WorkerInfo::weight).containsExactly(4, 2, 1);
    }

    @Test
    @DisplayName("round robin splits 30 requests 10/10/10, and every one ran on that node's Exp 2 executor (L3)")
    void roundRobinOnExp2Executors() {
        PhaseReport report = gateway.run(Strategy.ROUND_ROBIN, 30, 5, 6);

        assertThat(report.requestsPerNode()).isEqualTo(Map.of(1, 10, 2, 10, 3, 10));
        assertThat(report.failures()).isZero();
        assertThat(report.reroutes()).isZero();
        assertThat(report.makespanMillis()).isPositive();
        // The node publishes REQUEST_COMPLETED just after writing its reply, so wait for the last ones.
        await().atMost(Duration.ofSeconds(10))
                .until(() -> events("multithreading", "REQUEST_COMPLETED").size() == 30);
        List<ClusterEvent> served = events("multithreading", "REQUEST_COMPLETED");
        assertThat(served).hasSize(30).allSatisfy(e -> {
            assertThat(e.peerId()).isEqualTo(TcpWorkerTransport.SENDER_ID);
            assertThat((String) e.data().get("threadName")).startsWith("udcf-worker-n" + e.nodeId() + "-");
        });
        assertThat(bus.query(TcpWorkerTransport.MODULE, null, 100)).isEmpty();
    }

    @Test
    @DisplayName("smooth weighted round robin follows the static weights: 14 requests split 8/4/2")
    void weightedRoundRobin() {
        PhaseReport report = gateway.run(Strategy.WEIGHTED_ROUND_ROBIN, 14, 5, 3);

        assertThat(report.requestsPerNode()).isEqualTo(Map.of(1, 8, 2, 4, 3, 2));
        assertThat(report.failures()).isZero();
    }

    @Test
    @DisplayName("least connections and least response time serve every request over TCP")
    void liveStrategies() {
        for (Strategy s : List.of(Strategy.LEAST_CONNECTIONS, Strategy.LEAST_RESPONSE_TIME)) {
            PhaseReport report = gateway.run(s, 30, 5, 6);

            assertThat(report.served()).as(s.name()).isEqualTo(30);
            assertThat(report.failures()).as(s.name()).isZero();
        }
    }

    @Test
    @DisplayName("a node down at the start is rerouted around with zero failures while the other workers are healthy")
    void nodeDownAtStartWhileOthersHealthy() {
        cluster.crash(3);

        PhaseReport report = gateway.run(Strategy.ROUND_ROBIN, 30, 5, 6);

        assertThat(report.failures()).isZero();
        assertThat(report.served()).isEqualTo(30);
        assertThat(report.reroutes()).isPositive();
        assertThat(report.requestsPerNode()).containsEntry(3, 0);
        assertThat(worker(3).isHealthy()).isFalse();
        assertThat(worker(3).failed()).isPositive();
        assertThat(events(TcpWorkerTransport.MODULE, "DISPATCH_FAILED"))
                .anySatisfy(e -> {
                    assertThat(e.peerId()).isEqualTo(3);
                    assertThat(e.data()).containsEntry("reason", "ConnectException");
                });
        assertThat(RequestsNodeService.find(cluster.node(3))).isEmpty();   // never started on a crashed node
    }

    @Test
    @DisplayName("a node crashed with work in flight and queued: every request is still served while the other workers are healthy")
    void crashWithWorkInFlightAndQueuedWhileOthersHealthy() throws Exception {
        testThreads = Executors.newSingleThreadExecutor();
        // Payload 3000 on the SLOW node is about 480,000 hash rounds per request, so round
        // robin's share piles up there: one running and the rest queued.
        Future<PhaseReport> run = testThreads.submit(() -> gateway.run(Strategy.ROUND_ROBIN, 60, 3000, 12));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(2)).until(() ->
                RequestsNodeService.find(cluster.node(3)).flatMap(RequestsNodeService::snapshot)
                        .filter(s -> s.activeThreads() >= 1 && s.queuedRequests() >= 2).isPresent());
        cluster.crash(3);
        PhaseReport report = run.get(60, TimeUnit.SECONDS);

        assertThat(report.failures()).isZero();
        assertThat(report.served()).isEqualTo(60);
        assertThat(report.reroutes()).isGreaterThanOrEqualTo(2);
        // The running and queued requests all lost their connection: a crashed node sends no
        // reply at all (E2b), so none of them came back FAILED; each tripped the breaker.
        assertThat(worker(3).failed()).isGreaterThanOrEqualTo(2);
        assertThat(worker(3).declined()).isZero();
        assertThat(worker(3).isHealthy()).isFalse();
    }

    @Test
    @DisplayName("when every worker is crashed, every request fails promptly and nothing hangs")
    void allWorkersCrashed() {
        cluster.crash(1);
        cluster.crash(2);
        cluster.crash(3);

        PhaseReport report = gateway.run(Strategy.LEAST_CONNECTIONS, 10, 5, 4);

        assertThat(report.failures()).isEqualTo(10);
        assertThat(report.served()).isZero();
        assertThat(report.results()).allSatisfy(r -> {
            assertThat(r.succeeded()).isFalse();
            assertThat(r.nodeId()).isZero();
            assertThat(r.attempts()).isBetween(0, 3);
        });
        assertThat(gateway.workers()).noneMatch(WorkerInfo::isHealthy).allMatch(w -> w.failed() >= 1)
                .allMatch(w -> w.inFlight() == 0);
        assertThat(report.averageLatency()).isEmpty();
    }

    @Test
    @DisplayName("a recovered node is a worker again on the next run")
    void recoveredNodeServesNextRun() {
        cluster.crash(3);
        assertThat(gateway.run(Strategy.ROUND_ROBIN, 30, 5, 6).requestsPerNode()).containsEntry(3, 0);

        cluster.recover(3);
        PhaseReport after = gateway.run(Strategy.ROUND_ROBIN, 30, 5, 6);

        assertThat(after.requestsPerNode()).isEqualTo(Map.of(1, 10, 2, 10, 3, 10));
        assertThat(after.reroutes()).isZero();
        assertThat(worker(3).isHealthy()).isTrue();
    }

    @Test
    @DisplayName("the run id and the observer pass through: every result observed, DISPATCH events tagged with the run id")
    void runIdAndObserverPassThrough() {
        cluster.crash(3);
        AtomicInteger observed = new AtomicInteger();

        PhaseReport report = gateway.run(Strategy.ROUND_ROBIN, 12, 5, 3, "run-42", result -> observed.incrementAndGet());

        assertThat(observed).hasValue(12);
        assertThat(report.served()).isEqualTo(12);
        assertThat(events(TcpWorkerTransport.MODULE, "DISPATCH_FAILED")).isNotEmpty()
                .allSatisfy(e -> assertThat(e.data()).containsEntry("runId", "run-42"));
    }

    @Test
    @DisplayName("resetWorkers gives fresh workers with zero counters, all healthy")
    void resetWorkers() {
        cluster.crash(3);
        gateway.run(Strategy.ROUND_ROBIN, 12, 5, 3);
        assertThat(worker(3).isHealthy()).isFalse();

        gateway.resetWorkers();

        assertThat(gateway.workers()).allMatch(WorkerInfo::isHealthy)
                .allMatch(w -> w.completed() == 0 && w.failed() == 0 && w.declined() == 0);
    }

    @Test
    @DisplayName("invalid arguments are refused before any service starts")
    void validation() {
        assertThatThrownBy(() -> gateway.run(Strategy.ROUND_ROBIN, 1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.run(Strategy.ROUND_ROBIN, 1, 5001, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.run(Strategy.ROUND_ROBIN, 0, 5, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.run(Strategy.ROUND_ROBIN, 1, 5, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.run(null, 1, 5, 1)).isInstanceOf(NullPointerException.class);
        assertThat(cluster.nodes()).allSatisfy(n -> assertThat(RequestsNodeService.find(n)).isEmpty());

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        assertThatThrownBy(() -> new LoadBalancingGateway(null, MULTITHREADING, meters, bus, PROPERTIES))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LoadBalancingGateway(cluster, null, meters, bus, PROPERTIES))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LoadBalancingGateway(cluster, MULTITHREADING, null, bus, PROPERTIES))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LoadBalancingGateway(cluster, MULTITHREADING, meters, null, PROPERTIES))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LoadBalancingGateway(cluster, MULTITHREADING, meters, bus, null))
                .isInstanceOf(NullPointerException.class);
    }
}
