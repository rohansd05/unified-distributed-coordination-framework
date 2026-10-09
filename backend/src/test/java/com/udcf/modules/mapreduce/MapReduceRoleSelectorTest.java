package com.udcf.modules.mapreduce;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
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
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Unit tests for module-local MapReduce coordinator and worker selection.
 */
class MapReduceRoleSelectorTest {

    private static final ClusterProperties CLUSTER_CONFIG = new ClusterProperties(
            5,
            List.of(FAST, MEDIUM, SLOW, MEDIUM, FAST),
            new ClusterProperties.Ports(24100, 24200, 24300, 24400, 24500, 24600)
    );

    private Cluster cluster;
    private ClusterEventBus bus;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 1024), Clock.systemUTC());
        cluster = new Cluster(CLUSTER_CONFIG, bus);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    @Test
    @DisplayName("coordinator is the lowest live node id")
    void selectsLowestLiveNodeAsCoordinator() {
        assertThat(MapReduceRoleSelector.selectCoordinator(cluster)).isEqualTo(1);

        cluster.node(1).crash();
        assertThat(MapReduceRoleSelector.selectCoordinator(cluster)).isEqualTo(2);

        cluster.node(2).crash();
        assertThat(MapReduceRoleSelector.selectCoordinator(cluster)).isEqualTo(3);

        cluster.node(1).recover();
        assertThat(MapReduceRoleSelector.selectCoordinator(cluster)).isEqualTo(1);
    }

    @Test
    @DisplayName("throws IllegalStateException when no live nodes are available for coordinator")
    void throwsWhenAllNodesCrashed() {
        for (int i = 1; i <= 5; i++) {
            cluster.node(i).crash();
        }
        assertThatIllegalStateException()
                .isThrownBy(() -> MapReduceRoleSelector.selectCoordinator(cluster))
                .withMessageContaining("No live nodes available");
    }

    @Test
    @DisplayName("workers are all live nodes including the coordinator")
    void selectsAllLiveNodesAsWorkers() {
        assertThat(MapReduceRoleSelector.selectWorkers(cluster)).containsExactly(1, 2, 3, 4, 5);

        cluster.node(3).crash();
        assertThat(MapReduceRoleSelector.selectWorkers(cluster)).containsExactly(1, 2, 4, 5);

        cluster.node(1).crash();
        assertThat(MapReduceRoleSelector.selectWorkers(cluster)).containsExactly(2, 4, 5);
    }
}
