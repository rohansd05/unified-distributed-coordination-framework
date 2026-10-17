package com.udcf.core.cluster;

import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The shared role provider (link L1) on a five-node cluster with no services: the leader read from
 * the cluster, {@code ensureLeader} with and without an elector, ordered leader-change delivery on
 * the dispatcher thread, and the PRIMARY/BACKUP roles with {@code REPLICATION_ROLES_CHANGED}.
 *
 * <p>Port bases 26760 to 26790 (Track B block, below 32768): nothing here binds a socket.</p>
 */
class RoleProviderTest {

    private static final ClusterProperties PROPERTIES = new ClusterProperties(5, List.of(FAST, FAST, FAST, FAST, FAST),
            new ClusterProperties.Ports(26760, 26766, 26772, 26778, 26784, 26790));
    private static final long BOUND_MILLIS = 300;

    private ClusterEventBus bus;
    private Cluster cluster;
    private RoleProvider roles;

    /** Counts requests; elects {@code winner} from another thread after a short delay, or never if it is null. */
    private final class FakeElector implements LeaderElector {
        final AtomicInteger requests = new AtomicInteger();
        private final Integer winner;

        FakeElector(Integer winner) {
            this.winner = winner;
        }

        @Override
        public void requestElection() {
            requests.incrementAndGet();
            if (winner != null) {
                Thread.ofVirtual().start(() -> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    cluster.assignLeader(winner);
                });
            }
        }

        @Override
        public long electionBoundMillis() {
            return BOUND_MILLIS;
        }
    }

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), Clock.systemUTC());
        cluster = new Cluster(PROPERTIES, bus);
    }

    @AfterEach
    void tearDown() {
        if (roles != null) {
            roles.close();
        }
        cluster.close();
        bus.close();
    }

    private List<ClusterEvent> clusterEvents(String type) {
        return bus.query("cluster", 0, 5000).stream().filter(e -> e.type().equals(type)).toList();
    }

    private List<Integer> nodesWith(NodeRole role) {
        return cluster.nodes().stream().filter(n -> n.hasRole(role)).map(ClusterNode::id).toList();
    }

    @Test
    @DisplayName("leaderId reads the LEADER role: empty before an election and after the leader crashed")
    void leaderIdFollowsTheCluster() {
        roles = new RoleProvider(cluster, (LeaderElector) null);
        assertThat(roles.leaderId()).isEmpty();
        cluster.assignLeader(4);
        assertThat(roles.leaderId()).contains(4);
        cluster.crash(4);
        assertThat(roles.leaderId()).isEmpty();
    }

    @Test
    @DisplayName("ensureLeader returns an existing leader at once and asks for no election")
    void ensureLeaderReturnsExistingLeader() {
        FakeElector elector = new FakeElector(null);
        roles = new RoleProvider(cluster, elector);
        cluster.assignLeader(5);

        assertThat(roles.ensureLeader()).contains(5);
        assertThat(elector.requests).hasValue(0);
    }

    @Test
    @DisplayName("ensureLeader with no leader asks for one election and returns the leader as soon as it is assigned")
    void ensureLeaderRequestsOneElectionAndWaits() {
        FakeElector elector = new FakeElector(3);
        roles = new RoleProvider(cluster, elector);

        long start = System.nanoTime();
        Optional<Integer> leader = roles.ensureLeader();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(leader).contains(3);
        assertThat(elector.requests).hasValue(1);
        assertThat(elapsedMillis).isLessThan(BOUND_MILLIS);
    }

    @Test
    @DisplayName("ensureLeader returns empty after the elector's bound when nobody is elected; nothing falls back")
    void ensureLeaderReturnsEmptyAfterTheBound() {
        roles = new RoleProvider(cluster, new FakeElector(null));

        long start = System.nanoTime();
        Optional<Integer> leader = roles.ensureLeader();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(leader).isEmpty();
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(BOUND_MILLIS - 20).isLessThan(BOUND_MILLIS + 500);
        assertThat(nodesWith(NodeRole.LEADER)).isEmpty();
    }

    @Test
    @DisplayName("without an elector (no election module) ensureLeader returns empty at once")
    void ensureLeaderWithoutElectorReturnsEmptyAtOnce() {
        roles = new RoleProvider(cluster, (LeaderElector) null);

        long start = System.nanoTime();
        assertThat(roles.ensureLeader()).isEmpty();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(100);
    }

    @Test
    @DisplayName("a Spring context without the election module starts; ensureLeader is empty at once, leaderId works")
    void springContextWithoutElectionModuleStarts() {
        new ApplicationContextRunner()
                .withBean(Cluster.class, () -> cluster)
                .withBean(RoleProvider.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RoleProvider provider = context.getBean(RoleProvider.class);
                    assertThat(provider.ensureLeader()).isEmpty();
                    cluster.assignLeader(2);
                    assertThat(provider.leaderId()).contains(2);
                });
    }

    @Test
    @DisplayName("ensureLeader on the dispatcher thread (inside a leader listener) throws instead of waiting")
    void ensureLeaderOnTheDispatcherThrows() {
        roles = new RoleProvider(cluster, new FakeElector(null));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        roles.addLeaderListener(change -> {
            try {
                roles.ensureLeader();
            } catch (IllegalStateException e) {
                thrown.set(e);
            }
        });
        cluster.assignLeader(2);
        cluster.crash(2);   // LEADER_CRASHED: no leader while the listener runs

        await().atMost(Duration.ofSeconds(5)).until(() -> thrown.get() != null);
        assertThat(thrown.get()).hasMessageContaining(RoleProvider.DISPATCHER_THREAD);
    }

    @Test
    @DisplayName("listeners get every change in announcement order, with its cause, only on the dispatcher thread")
    void listenersGetChangesInOrderOnTheDispatcherThread() {
        roles = new RoleProvider(cluster, (LeaderElector) null);
        List<LeaderChange> changes = new CopyOnWriteArrayList<>();
        List<String> threads = new CopyOnWriteArrayList<>();
        roles.addLeaderListener(change -> {
            changes.add(change);
            threads.add(Thread.currentThread().getName());
        });

        cluster.assignLeader(1);
        cluster.assignLeader(2);
        cluster.crash(2);
        cluster.assignLeader(3);
        cluster.assignLeader(null);

        await().atMost(Duration.ofSeconds(5)).until(() -> changes.size() == 5);
        assertThat(changes).containsExactly(
                new LeaderChange(1, null, LeaderChange.Cause.ELECTED),
                new LeaderChange(2, 1, LeaderChange.Cause.ELECTED),
                new LeaderChange(null, 2, LeaderChange.Cause.LEADER_CRASHED),
                new LeaderChange(3, null, LeaderChange.Cause.ELECTED),
                new LeaderChange(null, 3, LeaderChange.Cause.CLEARED));
        assertThat(threads).containsOnly(RoleProvider.DISPATCHER_THREAD);
    }

    @Test
    @DisplayName("a failing listener does not stop the others; a closed registration receives nothing more")
    void failingAndClosedListeners() {
        roles = new RoleProvider(cluster, (LeaderElector) null);
        List<Integer> seen = new CopyOnWriteArrayList<>();
        List<Integer> closedSeen = new CopyOnWriteArrayList<>();
        roles.addLeaderListener(change -> {
            throw new IllegalStateException("listener failure (expected in this test)");
        });
        roles.addLeaderListener(change -> seen.add(change.leaderId()));
        RoleProvider.Registration closed = roles.addLeaderListener(change -> closedSeen.add(change.leaderId()));

        cluster.assignLeader(1);
        await().atMost(Duration.ofSeconds(5)).until(() -> seen.size() == 1);
        closed.close();
        cluster.assignLeader(2);

        await().atMost(Duration.ofSeconds(5)).until(() -> seen.size() == 2);
        assertThat(seen).containsExactly(1, 2);
        assertThat(closedSeen).containsExactly(1);
    }

    @Test
    @DisplayName("afterPendingChanges runs on the dispatcher, behind every change announced before it")
    void afterPendingChangesRunsBehindEarlierChanges() {
        roles = new RoleProvider(cluster, (LeaderElector) null);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> taskThread = new AtomicReference<>();
        roles.addLeaderListener(change -> order.add("change " + change.leaderId()));

        cluster.assignLeader(4);
        roles.afterPendingChanges(() -> {
            taskThread.set(Thread.currentThread().getName());
            order.add("task");
        });

        await().atMost(Duration.ofSeconds(5)).until(() -> order.size() == 2);
        assertThat(order).containsExactly("change 4", "task");
        assertThat(taskThread.get()).isEqualTo(RoleProvider.DISPATCHER_THREAD);
    }

    @Test
    @DisplayName("publishReplicationRoles: one PRIMARY, BACKUP on the listed nodes, announced only on a change; a switch keeps one primary")
    void publishReplicationRoles() {
        roles = new RoleProvider(cluster, (LeaderElector) null);

        assertThat(roles.publishReplicationRoles(2, List.of(1, 2, 3, 4))).contains(2);
        assertThat(nodesWith(NodeRole.PRIMARY)).containsExactly(2);
        assertThat(nodesWith(NodeRole.BACKUP)).containsExactly(1, 3, 4);
        assertThat(roles.primaryId()).contains(2);
        roles.publishReplicationRoles(2, List.of(4, 3, 1));   // the same roles: no new event
        assertThat(clusterEvents("REPLICATION_ROLES_CHANGED")).singleElement().satisfies(e -> {
            assertThat(e.peerId()).isEqualTo(2);
            assertThat(e.data()).containsEntry("primaryId", 2).containsEntry("previousPrimaryId", null)
                    .containsEntry("backupIds", List.of(1, 3, 4));
            assertThat(e.message()).isEqualTo("Node 2 is the replication primary; backups 1, 3, 4");
        });

        roles.publishReplicationRoles(5, List.of(1, 2));
        assertThat(nodesWith(NodeRole.PRIMARY)).containsExactly(5);
        assertThat(nodesWith(NodeRole.BACKUP)).containsExactly(1, 2);
        roles.publishReplicationRoles(null, List.of());
        assertThat(nodesWith(NodeRole.PRIMARY)).isEmpty();
        assertThat(nodesWith(NodeRole.BACKUP)).isEmpty();
        List<Map<String, Object>> data = clusterEvents("REPLICATION_ROLES_CHANGED").stream().map(ClusterEvent::data).toList();
        assertThat(data).hasSize(3);
        assertThat(data.get(1)).containsEntry("primaryId", 5).containsEntry("previousPrimaryId", 2);
        assertThat(data.get(2)).containsEntry("primaryId", null).containsEntry("previousPrimaryId", 5);
        assertThat(nodesWith(NodeRole.LEADER)).isEmpty();   // the replication roles never touch the leader
    }

    @Test
    @DisplayName("a crashed primary or backup loses its role and the change is announced; a crashed node is never granted one")
    void crashClearsReplicationRoles() {
        roles = new RoleProvider(cluster, (LeaderElector) null);
        roles.publishReplicationRoles(2, List.of(1, 3));

        cluster.crash(2);
        cluster.crash(3);

        List<Map<String, Object>> data = clusterEvents("REPLICATION_ROLES_CHANGED").stream().map(ClusterEvent::data).toList();
        assertThat(data).hasSize(3);
        assertThat(data.get(1)).containsEntry("primaryId", null).containsEntry("previousPrimaryId", 2)
                .containsEntry("backupIds", List.of(1, 3));
        assertThat(data.get(2)).containsEntry("primaryId", null).containsEntry("backupIds", List.of(1));
        assertThat(roles.primaryId()).isEmpty();

        assertThat(roles.publishReplicationRoles(3, List.of(1, 2))).isEmpty();
        assertThat(nodesWith(NodeRole.PRIMARY)).isEmpty();
        assertThat(nodesWith(NodeRole.BACKUP)).containsExactly(1);
        cluster.recover(2);
        assertThat(cluster.node(2).roles()).isEmpty();   // recovery restores no role
    }
}
