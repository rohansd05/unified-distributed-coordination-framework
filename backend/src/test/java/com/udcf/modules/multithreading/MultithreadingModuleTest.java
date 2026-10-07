package com.udcf.modules.multithreading;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.cluster.NodeStatus;
import com.udcf.core.cluster.UnknownNodeException;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.multithreading.dto.BatchDto;
import com.udcf.modules.multithreading.dto.BatchKind;
import com.udcf.modules.multithreading.dto.GenerateRequestsCommand;
import com.udcf.modules.multithreading.dto.MultithreadingOverviewDto;
import com.udcf.modules.multithreading.dto.NodeRequestsDto;
import com.udcf.modules.multithreading.dto.WorkloadDto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The module on a standalone three-node cluster (FAST, MEDIUM, SLOW) with real sockets. No
 * Spring context, so its crashes never disturb other tests.
 *
 * <p>Test-only port bases 45100 to 45600 (requests on 4550k): below the Windows dynamic
 * range and apart from every other test class. Queue capacity 20 and a backpressure demo of
 * 5 extra IO requests keep the demos short.</p>
 */
class MultithreadingModuleTest {

    private static final ClusterProperties CLUSTER = new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(45100, 45200, 45300, 45400, 45500, 45600));
    private static final MultithreadingProperties PROPERTIES = new MultithreadingProperties(20, 60,
            "udcf-worker-", 30, 500, 2000,
            new MultithreadingProperties.Backpressure(5, WorkloadType.IO_SIMULATED, 100));

    private ClusterEventBus bus;
    private Cluster cluster;
    private MultithreadingModule module;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), Clock.systemUTC());
        cluster = new Cluster(CLUSTER, bus);
        module = new MultithreadingModule(cluster, PROPERTIES, new SimpleMeterRegistry(), bus);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    private List<ClusterEvent> batchEvents(int nodeId) {
        return bus.query(MultithreadingModule.ID, nodeId, 5000).stream()
                .filter(event -> event.type().startsWith("BATCH_"))
                .toList();
    }

    private void awaitIdle() {
        await().atMost(10, TimeUnit.SECONDS).until(() -> module.status() == ModuleStatus.IDLE);
    }

    @Test
    @DisplayName("is lab 2, multithreading, and starts idle")
    void identity() {
        assertThat(module.id()).isEqualTo("multithreading");
        assertThat(module.labNumber()).isEqualTo(2);
        assertThat(module.title()).isEqualTo("Multithreading");
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
    }

    @Test
    @DisplayName("a batch starts the node's service lazily, reports exact counts, runs, and publishes one event pair")
    void batchRunsAndPublishesOnePair() {
        assertThat(RequestsNodeService.find(cluster.node(1))).isEmpty();

        BatchDto batch = module.submitBatch(1, GenerateRequestsCommand.of(8, WorkloadType.IO_SIMULATED, 250));

        assertThat(batch.kind()).isEqualTo(BatchKind.BATCH);
        assertThat(batch.nodeId()).isEqualTo(1);
        assertThat(batch.requested()).isEqualTo(8);
        assertThat(batch.accepted()).isEqualTo(8);
        assertThat(batch.rejected()).isZero();
        assertThat(batch.requestIds()).hasSize(8).doesNotHaveDuplicates();
        assertThat(RequestsNodeService.find(cluster.node(1))).isPresent();
        assertThat(module.status()).isEqualTo(ModuleStatus.RUNNING);   // 8 x 100 ms on 4 threads

        awaitIdle();
        await().atMost(5, TimeUnit.SECONDS).until(() -> batchEvents(1).size() == 2);
        ClusterEvent submitted = batchEvents(1).get(0);
        ClusterEvent finished = batchEvents(1).get(1);
        assertThat(submitted.type()).isEqualTo("BATCH_SUBMITTED");
        assertThat(submitted.data()).containsEntry("batchId", batch.batchId()).containsEntry("kind", "BATCH")
                .containsEntry("requested", 8).containsEntry("accepted", 8).containsEntry("rejected", 0)
                .containsEntry("workload", "IO_SIMULATED").containsEntry("payloadSize", 250);
        assertThat(finished.type()).isEqualTo("BATCH_FINISHED");
        assertThat(finished.data()).containsEntry("batchId", batch.batchId()).containsEntry("kind", "BATCH")
                .containsEntry("completed", 8L).containsEntry("failed", 0L).containsKey("elapsedMillis");
        assertThat((Long) finished.data().get("threads")).isBetween(2L, 4L);
        assertThat(finished.lamportTime()).isGreaterThan(submitted.lamportTime());
        assertThat(bus.query(MultithreadingModule.ID, 1, 5000)).extracting(ClusterEvent::type)
                .as("never one event per in-process request").doesNotContain("REQUEST_COMPLETED");
    }

    @Test
    @DisplayName("an oversized batch rejects exactly what the node cannot hold")
    void oversizedBatchRejectsExactly() {
        // SLOW node: 1 thread + queue 20 = 21 slots; the burst is held until fully submitted.
        BatchDto batch = module.submitBatch(3, GenerateRequestsCommand.of(30, WorkloadType.CPU_HASH, 1));

        assertThat(batch.accepted()).isEqualTo(21);
        assertThat(batch.rejected()).isEqualTo(9);
        awaitIdle();
    }

    @Test
    @DisplayName("backpressure rejects exactly the extra requests, keeps the module BUSY, and refuses a second demo")
    void backpressureIsDeterministicAndGuarded() {
        BatchDto demo = module.backpressure(1);   // FAST: 4 threads + 20 queue + 5 extra

        assertThat(demo.kind()).isEqualTo(BatchKind.BACKPRESSURE);
        assertThat(demo.requested()).isEqualTo(29);
        assertThat(demo.accepted()).isEqualTo(24);
        assertThat(demo.rejected()).isEqualTo(5);
        assertThat(module.status()).isEqualTo(ModuleStatus.BUSY);
        assertThat(module.overview().actionInProgress()).isEqualTo("Backpressure demo on node 1");
        assertThatThrownBy(() -> module.backpressure(2)).isInstanceOf(ModuleBusyException.class);

        awaitIdle();
        assertThat(module.overview().actionInProgress()).isNull();
        assertThat(module.backpressure(1).rejected()).isEqualTo(5);
        awaitIdle();
    }

    @Test
    @DisplayName("a crash mid-demo releases the guard, publishes no BATCH_FINISHED, and a later demo works")
    void crashMidDemoReleasesTheGuard() {
        BatchDto demo = module.backpressure(3);   // SLOW: 21 accepted, 160 ms each
        assertThat(module.status()).isEqualTo(ModuleStatus.BUSY);

        cluster.crash(3);

        await().atMost(5, TimeUnit.SECONDS).until(() -> module.status() != ModuleStatus.BUSY);
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(batchEvents(3)).extracting(event -> event.data().get("batchId") + " " + event.type())
                .containsExactly(demo.batchId() + " BATCH_SUBMITTED");
        cluster.recover(3);
        BatchDto again = module.backpressure(3);
        assertThat(again.rejected()).isEqualTo(5);
        cluster.crash(3);   // end the demo quickly
        awaitIdle();
    }

    @Test
    @DisplayName("a crashed or unknown node is refused, and a refused demo does not hold the guard")
    void downAndUnknownNodesAreRefused() {
        cluster.crash(2);

        assertThatThrownBy(() -> module.submitBatch(2, GenerateRequestsCommand.of(1, WorkloadType.CPU_HASH, 1)))
                .isInstanceOf(NodeDownException.class);
        assertThatThrownBy(() -> module.backpressure(2)).isInstanceOf(NodeDownException.class);
        assertThatThrownBy(() -> module.submitBatch(9, GenerateRequestsCommand.of(1, WorkloadType.CPU_HASH, 1)))
                .isInstanceOf(UnknownNodeException.class);
        assertThatThrownBy(() -> module.backpressure(9)).isInstanceOf(UnknownNodeException.class);
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(module.backpressure(1).rejected()).isEqualTo(5);
        awaitIdle();
    }

    @Test
    @DisplayName("reset clears the history but stops nothing and publishes nothing")
    void resetClearsHistoryOnly() {
        module.submitBatch(1, GenerateRequestsCommand.of(4, WorkloadType.CPU_HASH, 1));
        awaitIdle();
        assertThat(module.requests(1, 50)).hasSize(4);
        int eventsBefore = bus.query(null, null, 5000).size();

        module.reset();

        assertThat(module.requests(1, 50)).isEmpty();
        assertThat(RequestsNodeService.find(cluster.node(1))).get().extracting(RequestsNodeService::isRunning)
                .isEqualTo(true);
        assertThat(bus.query(null, null, 5000)).hasSize(eventsBefore);
    }

    @Test
    @DisplayName("reset is safe while a node crashes and recovers at the same moment")
    void resetRacesWithCrashAndRecover() throws InterruptedException {
        module.submitBatch(1, GenerateRequestsCommand.of(20, WorkloadType.CPU_HASH, 1));
        awaitIdle();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread lifecycle = new Thread(() -> {
            try {
                for (int i = 0; i < 100; i++) {
                    cluster.crash(1);
                    cluster.recover(1);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        Thread resets = new Thread(() -> {
            try {
                for (int i = 0; i < 500; i++) {
                    module.reset();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        lifecycle.start();
        resets.start();
        lifecycle.join();
        resets.join();

        assertThat(failure.get()).isNull();
        RequestRegistry history = RequestsNodeService.find(cluster.node(1)).orElseThrow().registry();
        assertThat(history.size()).isZero();
        assertThat(cluster.node(1).isUp()).isTrue();
        assertThat(module.submitBatch(1, GenerateRequestsCommand.of(3, WorkloadType.CPU_HASH, 1)).accepted()).isEqualTo(3);
        awaitIdle();
        assertThat(module.requests(1, 50)).hasSize(3);
    }

    @Test
    @DisplayName("requests are empty for a node never used, and still readable while the node is crashed")
    void requestsHistory() {
        assertThat(module.requests(2, 50)).isEmpty();
        assertThat(RequestsNodeService.find(cluster.node(2))).as("reading never starts a service").isEmpty();

        module.submitBatch(1, GenerateRequestsCommand.of(3, WorkloadType.CPU_HASH, 1));
        awaitIdle();
        cluster.crash(1);

        assertThat(module.requests(1, 50)).hasSize(3).allSatisfy(result ->
                assertThat(result.threadName()).startsWith("udcf-worker-n1-"));
        assertThat(module.requests(1, 2)).hasSize(2);
        assertThatThrownBy(() -> module.requests(7, 50)).isInstanceOf(UnknownNodeException.class);
    }

    @Test
    @DisplayName("the overview labels sleeps as simulated, not hashing, and says capacity is configured")
    void overviewIsHonest() {
        module.submitBatch(1, GenerateRequestsCommand.of(1, WorkloadType.CPU_HASH, 1));
        awaitIdle();

        MultithreadingOverviewDto overview = module.overview();

        assertThat(overview.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(overview.workloads()).extracting(WorkloadDto::type)
                .containsExactly(WorkloadType.CPU_HASH, WorkloadType.IO_SIMULATED, WorkloadType.MIXED);
        assertThat(overview.workloads().get(0).simulated()).isFalse();
        assertThat(overview.workloads().get(0).simulatedReason()).isNull();
        assertThat(overview.workloads().subList(1, 3)).allSatisfy(workload -> {
            assertThat(workload.simulated()).isTrue();
            assertThat(workload.simulatedReason()).contains("sleep").endsWith(".");
        });
        assertThat(overview.workloads()).allSatisfy(workload -> assertThat(workload.description()).endsWith("."));
        assertThat(overview.capacityNote()).contains("set in the configuration, not measured").endsWith(".");

        NodeRequestsDto fast = overview.nodes().get(0);
        NodeRequestsDto slow = overview.nodes().get(2);
        assertThat(overview.nodes()).extracting(NodeRequestsDto::nodeId).containsExactly(1, 2, 3);
        assertThat(overview.nodes()).allMatch(NodeRequestsDto::capacityConfigured);
        assertThat(fast.serviceRunning()).isTrue();
        assertThat(fast.stats()).isNotNull();
        assertThat(fast.stats().maxPoolSize()).isEqualTo(4);
        assertThat(fast.port()).isEqualTo(45501);
        assertThat(slow.capacity()).isEqualTo(NodeCapacity.SLOW);
        assertThat(slow.threads()).isEqualTo(1);
        assertThat(slow.workMultiplier()).isEqualTo(4);
        assertThat(slow.serviceRunning()).isFalse();
        assertThat(slow.stats()).isNull();
        assertThat(slow.nodeStatus()).isEqualTo(NodeStatus.UP);
    }
}
