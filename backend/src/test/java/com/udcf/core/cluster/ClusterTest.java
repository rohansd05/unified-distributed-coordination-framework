package com.udcf.core.cluster;

import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards how the shared cluster is built from configuration and that a crash is confined
 * to one node (R10).
 */
class ClusterTest {

    private static final ClusterProperties PROPERTIES = new ClusterProperties(5,
            List.of(FAST, MEDIUM, SLOW, MEDIUM, FAST),
            new ClusterProperties.Ports(1100, 6000, 7000, 7100, 7200, 7300));

    private ClusterEventBus bus;
    private Cluster cluster;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(1000, 1000), Clock.systemUTC());
        cluster = new Cluster(PROPERTIES, bus);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    private FakeNodeService ensure(int nodeId, String name) {
        return cluster.node(nodeId).ensureService(name, n -> new FakeNodeService(name));
    }

    @Test
    @DisplayName("builds nodes 1..N in order with the configured capacities and ports")
    void buildsNodes() {
        assertThat(cluster.size()).isEqualTo(5);
        assertThat(cluster.nodes()).extracting(ClusterNode::id).containsExactly(1, 2, 3, 4, 5);
        assertThat(cluster.nodes()).extracting(ClusterNode::capacity)
                .containsExactly(FAST, MEDIUM, SLOW, MEDIUM, FAST);
        assertThat(cluster.node(3).ports()).isEqualTo(new NodePorts(1103, 6003, 7003, 7103, 7203, 7303));
        assertThat(cluster.nodes()).allMatch(ClusterNode::isUp);
    }

    @Test
    @DisplayName("node ids outside 1..N throw UnknownNodeException")
    void unknownNode() {
        assertThatThrownBy(() -> cluster.node(0)).isInstanceOf(UnknownNodeException.class);
        assertThatThrownBy(() -> cluster.node(6)).isInstanceOf(UnknownNodeException.class)
                .satisfies(e -> assertThat(((UnknownNodeException) e).nodeId()).isEqualTo(6));
        assertThatThrownBy(() -> cluster.crash(6)).isInstanceOf(UnknownNodeException.class);
    }

    @Test
    @DisplayName("crash and recover delegate to the node and upCount tracks them")
    void crashAndRecoverDelegate() {
        assertThat(cluster.upCount()).isEqualTo(5);

        assertThat(cluster.crash(2)).isTrue();
        assertThat(cluster.crash(2)).isFalse();
        assertThat(cluster.node(2).status()).isEqualTo(NodeStatus.CRASHED);
        assertThat(cluster.upCount()).isEqualTo(4);

        assertThat(cluster.recover(2)).isTrue();
        assertThat(cluster.recover(2)).isFalse();
        assertThat(cluster.upCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("recoverAll brings every crashed node back")
    void recoverAll() {
        cluster.crash(1);
        cluster.crash(3);
        cluster.crash(5);

        cluster.recoverAll();

        assertThat(cluster.upCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("CLUSTER_STARTED is published once with node id 0, size and capacities")
    void clusterStartedPublishedOnce() {
        cluster.crash(1);

        List<ClusterEvent> started = bus.query("cluster", 0, 1000).stream()
                .filter(e -> e.type().equals("CLUSTER_STARTED"))
                .toList();

        assertThat(started).singleElement().satisfies(e -> {
            assertThat(e.nodeId()).isZero();
            assertThat(e.data()).containsEntry("size", 5)
                    .containsEntry("capacities", List.of("FAST", "MEDIUM", "SLOW", "MEDIUM", "FAST"));
        });
    }

    @Test
    @DisplayName("crashing one node leaves the other nodes' services running")
    void crashIsConfinedToOneNode() {
        FakeNodeService onOne = ensure(1, "election");
        FakeNodeService onTwo = ensure(2, "election");
        FakeNodeService onThree = ensure(3, "replication");

        cluster.crash(2);

        assertThat(onTwo.isRunning()).isFalse();
        assertThat(onOne.isRunning()).isTrue();
        assertThat(onThree.isRunning()).isTrue();
        assertThat(cluster.node(1).runningServices()).containsExactly("election");
    }

    @Test
    @DisplayName("close stops every node's services and is idempotent")
    void closeStopsEverything() {
        FakeNodeService onOne = ensure(1, "election");
        FakeNodeService onFive = ensure(5, "requests");

        cluster.close();
        cluster.close();

        assertThat(onOne.count("stop")).isEqualTo(1);
        assertThat(onFive.count("stop")).isEqualTo(1);
        assertThat(cluster.nodes()).allMatch(node -> node.runningServices().isEmpty());
    }

    @Test
    @DisplayName("nodes() is unmodifiable")
    void nodesUnmodifiable() {
        assertThatThrownBy(() -> cluster.nodes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("resetClocks sets every node clock and the cluster clock to zero")
    void resetClocks() {
        cluster.crash(1);
        cluster.recover(1);
        cluster.node(4).clock().update(99);
        assertThat(cluster.clusterClock().current()).isPositive();

        cluster.resetClocks();

        assertThat(cluster.nodes()).allSatisfy(node -> assertThat(node.clock().current()).isZero());
        assertThat(cluster.clusterClock().current()).isZero();
    }

    @Test
    @DisplayName("clusterClock is the clock behind CLUSTER_STARTED")
    void clusterClockIsTheStartedClock() {
        ClusterEvent started = bus.query("cluster", 0, 1000).get(0);

        assertThat(started.type()).isEqualTo("CLUSTER_STARTED");
        assertThat(started.lamportTime()).isEqualTo(1);
        assertThat(cluster.clusterClock().current()).isEqualTo(1);
        assertThat(cluster.clusterClock()).isSameAs(cluster.clusterClock());
        assertThat(cluster.clusterClock().tick()).isEqualTo(2);
    }
}
