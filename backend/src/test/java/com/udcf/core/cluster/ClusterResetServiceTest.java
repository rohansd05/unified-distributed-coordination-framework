package com.udcf.core.cluster;

import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.core.events.EventProperties;
import com.udcf.core.module.ExperimentModule;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.core.module.ModuleRegistry;
import com.udcf.core.module.ModuleStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Guards the clean-slate reset: refused with nothing changed while a module is busy;
 * otherwise every node up, every module reset, every clock at zero, and exactly one
 * CLUSTER_RESET event left. Resets never interleave.
 */
class ClusterResetServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ClusterProperties PROPERTIES = new ClusterProperties(3,
            List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(1100, 6000, 7000, 7100, 7200, 7300));

    /** A module whose status the test controls and whose resets are counted. */
    private static final class FakeModule implements ExperimentModule {
        private final String id;
        private final int labNumber;
        private volatile ModuleStatus status = ModuleStatus.IDLE;
        private final AtomicInteger resets = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;

        FakeModule(String id, int labNumber) {
            this.id = id;
            this.labNumber = labNumber;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int labNumber() {
            return labNumber;
        }

        @Override
        public String title() {
            return "Fake " + id;
        }

        @Override
        public ModuleStatus status() {
            return status;
        }

        @Override
        public void reset() {
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                resets.incrementAndGet();
                CountDownLatch enteredLatch = entered;
                if (enteredLatch != null) {
                    enteredLatch.countDown();
                    release.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
        }
    }

    private ClusterEventBus bus;
    private Cluster cluster;
    private FakeModule alpha;
    private FakeModule beta;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(1000, 1000), Clock.systemUTC());
        cluster = new Cluster(PROPERTIES, bus);
        alpha = new FakeModule("alpha", 1);
        beta = new FakeModule("beta", 2);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    private static ModuleRegistry registryOf(ExperimentModule... modules) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        for (int i = 0; i < modules.length; i++) {
            factory.addBean("module" + i, modules[i]);
        }
        return new ModuleRegistry(factory.getBeanProvider(ExperimentModule.class));
    }

    @Test
    @DisplayName("a busy module refuses the reset and nothing changes")
    void busyModuleRefusesReset() {
        ClusterResetService service = new ClusterResetService(cluster, bus, registryOf(alpha, beta));
        cluster.crash(2);
        bus.publish(EventDraft.of("alpha", 1, "WORK", cluster.node(1).clock().tick()));
        List<ClusterEvent> historyBefore = bus.query(null, null, 1000);
        long node2ClockBefore = cluster.node(2).clock().current();
        beta.status = ModuleStatus.BUSY;

        assertThatThrownBy(service::reset)
                .isInstanceOf(ModuleBusyException.class)
                .satisfies(e -> {
                    ModuleBusyException busy = (ModuleBusyException) e;
                    assertThat(busy.moduleId()).isEqualTo("beta");
                    assertThat(busy.actionInProgress()).isEqualTo("a long-running action");
                });

        assertThat(cluster.node(2).status()).isEqualTo(NodeStatus.CRASHED);
        assertThat(bus.query(null, null, 1000)).isEqualTo(historyBefore);
        assertThat(cluster.node(2).clock().current()).isEqualTo(node2ClockBefore);
        assertThat(alpha.resets).hasValue(0);
        assertThat(beta.resets).hasValue(0);
    }

    @Test
    @DisplayName("a reset recovers crashed nodes, resets each module once and zeroes every node clock")
    void resetRestoresCleanState() {
        ClusterResetService service = new ClusterResetService(cluster, bus, registryOf(alpha, beta));
        cluster.crash(1);
        cluster.crash(3);
        cluster.node(2).clock().update(50);

        service.reset();

        assertThat(cluster.upCount()).isEqualTo(3);
        assertThat(alpha.resets).hasValue(1);
        assertThat(beta.resets).hasValue(1);
        assertThat(cluster.nodes()).allSatisfy(node -> assertThat(node.clock().current()).isZero());
        assertThat(cluster.clusterClock().current()).isEqualTo(1);
    }

    @Test
    @DisplayName("exactly one event remains: CLUSTER_RESET, node 0, Lamport time 1, a fresh sequence")
    void leavesOnlyClusterReset() {
        ClusterResetService service = new ClusterResetService(cluster, bus, registryOf(alpha));
        cluster.crash(2);
        long highestBefore = bus.query(null, null, 1000).stream()
                .mapToLong(ClusterEvent::sequence).max().orElseThrow();

        service.reset();

        List<ClusterEvent> history = bus.query(null, null, 1000);
        assertThat(history).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("CLUSTER_RESET");
            assertThat(event.module()).isEqualTo("cluster");
            assertThat(event.nodeId()).isZero();
            assertThat(event.lamportTime()).isEqualTo(1);
            assertThat(event.message()).isEqualTo("Cluster reset to a clean state");
            assertThat(event.data()).isEqualTo(Map.of("size", 3));
            assertThat(event.sequence()).isGreaterThan(highestBefore);
        });
    }

    @Test
    @DisplayName("two concurrent resets never interleave")
    void concurrentResetsDoNotInterleave() throws Exception {
        ClusterResetService service = new ClusterResetService(cluster, bus, registryOf(alpha));
        alpha.entered = new CountDownLatch(1);
        alpha.release = new CountDownLatch(1);

        Thread first = new Thread(service::reset, "reset-1");
        first.start();
        assertThat(alpha.entered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        // The first reset now holds the lock inside alpha.reset().

        alpha.entered = null;
        Thread second = new Thread(service::reset, "reset-2");
        second.start();
        await().atMost(TIMEOUT).until(() -> second.getState() == Thread.State.WAITING
                || second.getState() == Thread.State.TIMED_WAITING);
        assertThat(alpha.resets).hasValue(1);

        alpha.release.countDown();
        first.join(TIMEOUT.toMillis());
        second.join(TIMEOUT.toMillis());

        assertThat(first.isAlive()).isFalse();
        assertThat(second.isAlive()).isFalse();
        assertThat(alpha.resets).hasValue(2);
        assertThat(alpha.maxActive).hasValue(1);
        assertThat(bus.query(null, null, 1000)).extracting(ClusterEvent::type).containsExactly("CLUSTER_RESET");
    }

    @Test
    @DisplayName("a reset works with no modules registered")
    void resetWithNoModules() {
        ClusterResetService service = new ClusterResetService(cluster, bus, registryOf());
        cluster.crash(1);

        service.reset();

        assertThat(cluster.upCount()).isEqualTo(3);
        assertThat(bus.query(null, null, 1000)).extracting(ClusterEvent::type).containsExactly("CLUSTER_RESET");
    }
}
