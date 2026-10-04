package com.udcf.core.cluster;

import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the node lifecycle every module depends on: a crash takes down every running
 * service, recovery brings back exactly those, failures are reported rather than thrown,
 * and the node's Lamport clock is never reset.
 */
class ClusterNodeTest {

    private static final NodePorts PORTS = new NodePorts(1101, 6001, 7001, 7101, 7201, 7301);

    private ClusterEventBus bus;
    private ClusterNode node;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), Clock.systemUTC());
        node = new ClusterNode(1, NodeCapacity.FAST, PORTS, bus);
    }

    @AfterEach
    void tearDown() {
        bus.close();
    }

    private List<ClusterEvent> events() {
        return bus.query("cluster", 1, 5000);
    }

    private List<String> eventTypes() {
        return events().stream().map(ClusterEvent::type).toList();
    }

    private ClusterEvent lastEvent(String type) {
        List<ClusterEvent> matching = events().stream().filter(e -> e.type().equals(type)).toList();
        assertThat(matching).as("events of type %s", type).isNotEmpty();
        return matching.get(matching.size() - 1);
    }

    private FakeNodeService ensure(String name) {
        return node.ensureService(name, n -> new FakeNodeService(name));
    }

    @Test
    @DisplayName("starts UP with no services and keeps its identity")
    void startsUp() {
        assertThat(node.status()).isEqualTo(NodeStatus.UP);
        assertThat(node.isUp()).isTrue();
        assertThat(node.id()).isEqualTo(1);
        assertThat(node.capacity()).isEqualTo(NodeCapacity.FAST);
        assertThat(node.ports()).isEqualTo(PORTS);
        assertThat(node.runningServices()).isEmpty();
        assertThat(node.clock().current()).isZero();
    }

    @Test
    @DisplayName("ensureService creates and starts a service once and returns the same instance")
    void ensureServiceCreatesOnce() {
        AtomicInteger factoryCalls = new AtomicInteger();

        FakeNodeService first = node.ensureService("election", n -> {
            factoryCalls.incrementAndGet();
            return new FakeNodeService("election");
        });
        FakeNodeService second = node.ensureService("election", n -> {
            factoryCalls.incrementAndGet();
            return new FakeNodeService("election");
        });

        assertThat(second).isSameAs(first);
        assertThat(factoryCalls).hasValue(1);
        assertThat(first.calls()).containsExactly("start");
        assertThat(node.runningServices()).containsExactly("election");
        assertThat(node.service("election")).containsSame(first);
        assertThat(lastEvent("SERVICE_STARTED").data()).containsEntry("service", "election");
    }

    @Test
    @DisplayName("a factory returning a differently named service is rejected and nothing is started")
    void factoryNameMismatchRejected() {
        FakeNodeService wrong = new FakeNodeService("replication");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> node.ensureService("election", n -> wrong))
                .withMessageContaining("replication");

        assertThat(wrong.calls()).isEmpty();
        assertThat(node.service("election")).isEmpty();
    }

    @Test
    @DisplayName("on a crashed node ensureService throws NodeDownException without calling the factory")
    void ensureServiceOnCrashedNode() {
        node.crash();
        AtomicInteger factoryCalls = new AtomicInteger();

        assertThatThrownBy(() -> node.ensureService("election", n -> {
            factoryCalls.incrementAndGet();
            return new FakeNodeService("election");
        })).isInstanceOf(NodeDownException.class)
                .satisfies(e -> assertThat(((NodeDownException) e).nodeId()).isEqualTo(1));

        assertThat(factoryCalls).hasValue(0);
    }

    @Test
    @DisplayName("a start failure registers nothing, publishes SERVICE_START_FAILED and rethrows")
    void startFailure() {
        FakeNodeService failing = new FakeNodeService("election");
        failing.failStart = true;

        assertThatIllegalStateException()
                .isThrownBy(() -> node.ensureService("election", n -> failing))
                .withCauseInstanceOf(IllegalStateException.class);

        assertThat(node.service("election")).isEmpty();
        assertThat(eventTypes()).containsExactly("SERVICE_START_FAILED");
        assertThat(lastEvent("SERVICE_START_FAILED").data())
                .containsEntry("service", "election")
                .containsKey("error");
    }

    @Test
    @DisplayName("crash crashes only running services, sets CRASHED and publishes NODE_CRASHED")
    void crashCrashesRunningServices() {
        FakeNodeService election = ensure("election");
        FakeNodeService replication = ensure("replication");
        FakeNodeService idle = ensure("idle");
        idle.stop();   // registered but not running

        assertThat(node.crash()).isTrue();

        assertThat(node.status()).isEqualTo(NodeStatus.CRASHED);
        assertThat(election.count("crash")).isEqualTo(1);
        assertThat(replication.count("crash")).isEqualTo(1);
        assertThat(idle.count("crash")).isZero();
        assertThat(node.runningServices()).isEmpty();
        ClusterEvent crashed = lastEvent("NODE_CRASHED");
        assertThat(crashed.data()).containsEntry("services", List.of("election", "replication"))
                .containsEntry("failures", List.of());
    }

    @Test
    @DisplayName("a second crash returns false and publishes nothing")
    void secondCrashIsNoOp() {
        ensure("election");
        node.crash();
        int eventsBefore = events().size();

        assertThat(node.crash()).isFalse();

        assertThat(events()).hasSize(eventsBefore);
    }

    @Test
    @DisplayName("crash continues past a throwing service and reports it")
    void crashContinuesPastFailure() {
        FakeNodeService failing = ensure("election");
        FakeNodeService healthy = ensure("replication");
        failing.failCrash = true;

        assertThat(node.crash()).isTrue();

        assertThat(node.status()).isEqualTo(NodeStatus.CRASHED);
        assertThat(healthy.count("crash")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> failures = (List<Map<String, String>>) lastEvent("NODE_CRASHED").data().get("failures");
        assertThat(failures).singleElement()
                .satisfies(f -> assertThat(f).containsEntry("service", "election").containsKey("error"));
    }

    @Test
    @DisplayName("recover calls recover() on exactly the services running before the crash")
    void recoverRestoresPreviouslyRunning() {
        FakeNodeService election = ensure("election");
        FakeNodeService replication = ensure("replication");
        FakeNodeService idle = ensure("idle");
        idle.stop();
        node.crash();

        assertThat(node.recover()).isTrue();

        assertThat(node.status()).isEqualTo(NodeStatus.UP);
        assertThat(election.calls()).containsExactly("start", "crash", "recover");
        assertThat(replication.calls()).containsExactly("start", "crash", "recover");
        assertThat(idle.count("recover")).isZero();
        assertThat(node.runningServices()).containsExactly("election", "replication");
        assertThat(lastEvent("NODE_RECOVERED").data())
                .containsEntry("services", List.of("election", "replication"))
                .containsEntry("failures", List.of());
    }

    @Test
    @DisplayName("recover on an UP node returns false and publishes nothing")
    void recoverWhenUpIsNoOp() {
        ensure("election");
        int eventsBefore = events().size();

        assertThat(node.recover()).isFalse();

        assertThat(events()).hasSize(eventsBefore);
    }

    @Test
    @DisplayName("a failed recovery leaves the node UP, is reported, and the next ensureService retries it")
    void failedRecoveryIsRetriedByEnsureService() {
        FakeNodeService election = ensure("election");
        node.crash();
        election.failRecover = true;

        assertThat(node.recover()).isTrue();

        assertThat(node.isUp()).isTrue();
        assertThat(election.isRunning()).isFalse();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> failures = (List<Map<String, String>>) lastEvent("NODE_RECOVERED").data().get("failures");
        assertThat(failures).singleElement()
                .satisfies(f -> assertThat(f).containsEntry("service", "election"));

        election.failRecover = false;
        AtomicInteger factoryCalls = new AtomicInteger();
        FakeNodeService again = node.ensureService("election", n -> {
            factoryCalls.incrementAndGet();
            return new FakeNodeService("election");
        });

        assertThat(again).isSameAs(election);
        assertThat(factoryCalls).hasValue(0);
        assertThat(election.isRunning()).isTrue();
        assertThat(election.count("recover")).isEqualTo(2);
        assertThat(lastEvent("SERVICE_RECOVERED").data()).containsEntry("service", "election");
    }

    @Test
    @DisplayName("a recovery that fails again inside ensureService throws, keeps the service and publishes SERVICE_RECOVER_FAILED")
    void retriedRecoveryFailure() {
        FakeNodeService election = ensure("election");
        node.crash();
        election.failRecover = true;
        node.recover();

        assertThatIllegalStateException().isThrownBy(() -> ensure("election"));

        assertThat(node.service("election")).containsSame(election);
        assertThat(lastEvent("SERVICE_RECOVER_FAILED").data())
                .containsEntry("service", "election")
                .containsKey("error");
    }

    @Test
    @DisplayName("the clock survives crash and recovery and never decreases")
    void clockSurvivesCrashAndRecovery() {
        ensure("election");
        node.clock().tick();
        node.clock().update(40);
        long beforeCrash = node.clock().current();

        node.crash();
        long afterCrash = node.clock().current();
        node.recover();
        long afterRecover = node.clock().current();
        node.stop();
        long afterStop = node.clock().current();

        assertThat(afterCrash).isGreaterThan(beforeCrash);
        assertThat(afterRecover).isGreaterThan(afterCrash);
        assertThat(afterStop).isGreaterThanOrEqualTo(afterRecover);
        List<Long> lamportTimes = events().stream().map(ClusterEvent::lamportTime).toList();
        assertThat(lamportTimes).isSorted().doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("stop stops every registered service and continues past failures")
    void stopStopsEveryService() {
        FakeNodeService election = ensure("election");
        FakeNodeService replication = ensure("replication");
        election.failStop = true;

        node.stop();

        assertThat(election.count("stop")).isEqualTo(1);
        assertThat(replication.count("stop")).isEqualTo(1);
        assertThat(node.runningServices()).isEmpty();
    }

    @Test
    @DisplayName("every event uses module cluster, this node's id and increasing Lamport times")
    void eventShape() {
        ensure("election");
        node.crash();
        node.recover();

        List<ClusterEvent> events = events();
        assertThat(events).extracting(ClusterEvent::type)
                .containsExactly("SERVICE_STARTED", "NODE_CRASHED", "NODE_RECOVERED");
        assertThat(events).allSatisfy(e -> {
            assertThat(e.module()).isEqualTo("cluster");
            assertThat(e.nodeId()).isEqualTo(1);
        });
        assertThat(events).extracting(ClusterEvent::lamportTime).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("20 threads crashing, recovering and ensuring services leave a consistent state")
    void concurrentLifecycleStaysConsistent() throws Exception {
        int threads = 20;
        int opsPerThread = 500;
        List<String> names = List.of("election", "replication", "requests");
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Random random = new Random(42L + t);
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        try {
                            switch (random.nextInt(3)) {
                                case 0 -> node.crash();
                                case 1 -> node.recover();
                                default -> ensure(names.get(random.nextInt(names.size())));
                            }
                        } catch (NodeDownException expected) {
                            // racing a crash is allowed
                        } catch (Throwable other) {
                            unexpected.add(other);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected).isEmpty();
        List<NodeService> registered = names.stream()
                .flatMap(name -> node.service(name).stream())
                .toList();
        if (node.status() == NodeStatus.CRASHED) {
            assertThat(registered).noneMatch(NodeService::isRunning);
        } else {
            assertThat(registered).allMatch(NodeService::isRunning);
        }
    }
}
