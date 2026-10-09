package com.udcf.core.cluster;

import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.web.dto.NodeDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The cluster roles API: one LEADER at a time, never on a crashed node, removed by a crash and
 * not restored by recovery, announced with LEADER_CHANGED, and safe against the crash path that
 * joins election threads.
 *
 * <p>Port bases 26600 to 26650 (Track B block, below 32768): nothing here binds a socket.</p>
 */
class ClusterRolesTest {

    private static final ClusterProperties PROPERTIES = new ClusterProperties(5,
            List.of(FAST, MEDIUM, SLOW, MEDIUM, FAST),
            new ClusterProperties.Ports(26600, 26610, 26620, 26630, 26640, 26650));

    private ClusterEventBus bus;
    private Cluster cluster;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), Clock.systemUTC());
        cluster = new Cluster(PROPERTIES, bus);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    private List<ClusterEvent> leaderChanges() {
        return bus.query("cluster", 0, 5000).stream().filter(e -> e.type().equals("LEADER_CHANGED")).toList();
    }

    private List<Integer> nodesWithLeaderRole() {
        return cluster.nodes().stream().filter(n -> n.hasRole(NodeRole.LEADER)).map(ClusterNode::id).toList();
    }

    @Test
    @DisplayName("assignLeader keeps exactly one LEADER and announces every change on the cluster topic")
    void assignLeaderGrantsOnlyOneLeaderAndPublishesChange() {
        assertThat(cluster.assignLeader(3)).contains(3);
        assertThat(cluster.assignLeader(5)).contains(5);
        assertThat(nodesWithLeaderRole()).containsExactly(5);
        assertThat(cluster.leaderId()).contains(5);
        assertThat(cluster.node(5).roles()).containsExactly(NodeRole.LEADER);

        List<ClusterEvent> changes = leaderChanges();
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).data()).containsEntry("leaderId", 3).containsEntry("previousLeaderId", null);
        assertThat(changes.get(1).data()).containsEntry("leaderId", 5).containsEntry("previousLeaderId", 3);
        assertThat(changes).extracting(ClusterEvent::nodeId).containsOnly(0);
        assertThat(changes).extracting(ClusterEvent::peerId).containsExactly(3, 5);
    }

    @Test
    @DisplayName("assigning the current leader again, or no leader when there is none, publishes nothing")
    void assignSameLeaderPublishesNothing() {
        cluster.assignLeader(null);
        cluster.assignLeader(2);
        cluster.assignLeader(2);
        assertThat(leaderChanges()).hasSize(1);
        assertThat(cluster.assignLeader(null)).isEmpty();
        assertThat(nodesWithLeaderRole()).isEmpty();
        assertThat(leaderChanges()).hasSize(2);
        assertThatThrownBy(() -> cluster.assignLeader(9)).isInstanceOf(UnknownNodeException.class);
    }

    @Test
    @DisplayName("a crashed node cannot become leader")
    void crashedNodeCannotBecomeLeader() {
        cluster.crash(2);
        assertThat(cluster.assignLeader(2)).isEmpty();
        assertThat(nodesWithLeaderRole()).isEmpty();
        assertThat(leaderChanges()).isEmpty();
    }

    @Test
    @DisplayName("crashing the leader removes its role and announces that there is no leader")
    void crashClearsRolesAndPublishesLeaderLost() {
        cluster.assignLeader(4);
        cluster.crash(4);
        assertThat(cluster.node(4).roles()).isEmpty();
        assertThat(cluster.leaderId()).isEmpty();
        ClusterEvent last = leaderChanges().get(leaderChanges().size() - 1);
        assertThat(last.data()).containsEntry("leaderId", null).containsEntry("previousLeaderId", 4);
        assertThat(last.peerId()).isNull();
    }

    @Test
    @DisplayName("crashing a node that is not the leader changes no role and announces nothing")
    void crashingNonLeaderPublishesNothing() {
        cluster.assignLeader(4);
        cluster.crash(2);
        assertThat(cluster.leaderId()).contains(4);
        assertThat(leaderChanges()).hasSize(1);
    }

    @Test
    @DisplayName("recovery does not give a role back")
    void recoverDoesNotRestoreRoles() {
        cluster.assignLeader(4);
        cluster.crash(4);
        cluster.recover(4);
        assertThat(cluster.node(4).roles()).isEmpty();
        assertThat(cluster.leaderId()).isEmpty();
    }

    @Test
    @DisplayName("NodeDto lists the role names: [\"LEADER\"] on the leader, [] elsewhere")
    void nodeDtoCarriesLeaderRole() {
        cluster.assignLeader(5);
        assertThat(NodeDto.from(cluster.node(5)).roles()).containsExactly("LEADER");
        assertThat(NodeDto.from(cluster.node(1)).roles()).isEmpty();
    }

    @Test
    @DisplayName("an election thread can assign the leader while the cluster crashes a node and joins that thread")
    void crashRacingWithLeaderAssignmentDoesNotDeadlock() {
        cluster.assignLeader(5);
        AtomicReference<Optional<Integer>> assignedDuringCrash = new AtomicReference<>();
        AtomicReference<Boolean> workerJoined = new AtomicReference<>();
        // Like ElectionNodeService.crash(): runs under the node's lifecycle lock and joins a worker
        // that is, at that moment, assigning the leader.
        NodeService joiningService = new NodeService() {
            private volatile boolean running = true;

            @Override
            public String name() {
                return "joining";
            }

            @Override
            public void start() {
                running = true;
            }

            @Override
            public void crash() {
                Thread worker = new Thread(() -> assignedDuringCrash.set(cluster.assignLeader(5)), "fake-election-worker");
                worker.start();
                try {
                    workerJoined.set(worker.join(Duration.ofSeconds(5)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    workerJoined.set(false);
                }
                running = false;
            }

            @Override
            public void recover() {
                running = true;
            }

            @Override
            public void stop() {
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
        cluster.node(5).ensureService("joining", n -> joiningService);

        CompletableFuture<Boolean> crash = CompletableFuture.supplyAsync(() -> cluster.crash(5));
        await().atMost(Duration.ofSeconds(10)).until(crash::isDone);

        assertThat(crash.join()).isTrue();
        assertThat(workerJoined.get()).isTrue();
        assertThat(assignedDuringCrash.get()).contains(5);   // granted while the node was still UP mid-crash
        assertThat(cluster.node(5).roles()).isEmpty();        // then cleared by the crash
        assertThat(cluster.leaderId()).isEmpty();
        ClusterEvent last = leaderChanges().get(leaderChanges().size() - 1);
        assertThat(last.data()).containsEntry("leaderId", null).containsEntry("previousLeaderId", 5);
    }
}
