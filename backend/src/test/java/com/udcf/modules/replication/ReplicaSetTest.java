package com.udcf.modules.replication;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Clock;
import java.util.List;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The replica-set facade on a standalone three-node cluster with real sockets.
 *
 * <p>Test-only port bases 28110 to 28610, so node k's replication port is 2841k: below Linux's
 * ephemeral range and Windows' dynamic range, apart from every other test class.</p>
 */
class ReplicaSetTest {

    private static final ClusterProperties CLUSTER = new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(28110, 28210, 28310, 28410, 28510, 28610));
    private static final ReplicationProperties PROPERTIES = new ReplicationProperties(50, 2000, 200);

    private ClusterEventBus bus;
    private Cluster cluster;
    private ReplicaSet replicas;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), Clock.systemUTC());
        cluster = new Cluster(CLUSTER, bus);
        replicas = new ReplicaSet(cluster, PROPERTIES, bus);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    private String valueOn(int nodeId, String key) throws IOException {
        return replicas.read(nodeId, key).item().map(DataItem::value).orElse(null);
    }

    @Test
    @DisplayName("the primary is the lowest live node; with every node crashed there is none")
    void primaryIsLowestLive() {
        assertThat(replicas.primaryId()).isEqualTo(1);
        cluster.crash(1);
        assertThat(replicas.primaryId()).isEqualTo(2);
        cluster.crash(2);
        assertThat(replicas.primaryId()).isEqualTo(3);
        cluster.crash(3);
        assertThatThrownBy(replicas::primaryId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("every other node is a backup, crashed ones included")
    void backupsIncludeCrashed() {
        cluster.crash(3);
        assertThat(replicas.backupIds()).containsExactly(2, 3);
        cluster.crash(1);
        assertThat(replicas.backupIds()).containsExactly(1, 3);
    }

    @Test
    @DisplayName("a write goes through the selected primary at epoch 1 and starts the service on every live backup")
    void writeRoutesThroughPrimaryAndStartsBackups() throws IOException {
        WriteResult result = replicas.write("k", "v", ConsistencyModel.SYNCHRONOUS);

        assertThat(result.item().originNode()).isEqualTo(1);
        assertThat(result.item().epoch()).isEqualTo(1);
        assertThat(result.backupIds()).containsExactly(2, 3);
        assertThat(replicas.service(1).isPrimary()).isTrue();
        assertThat(cluster.nodes()).allSatisfy(node -> assertThat(node.runningServices())
                .contains(ReplicationNodeService.NAME));
        assertThat(valueOn(2, "k")).isEqualTo("v");
        assertThat(valueOn(3, "k")).isEqualTo("v");
    }

    @Test
    @DisplayName("crashing node 1 moves the primary to node 2; recovering node 1 gives it back and node 2 steps down")
    void primaryFollowsTheSelector() throws IOException {
        replicas.write("k", "a", ConsistencyModel.SYNCHRONOUS);
        cluster.crash(1);

        WriteResult second = replicas.write("k", "b", ConsistencyModel.SYNCHRONOUS);

        assertThat(second.item().originNode()).isEqualTo(2);
        assertThat(second.backupIds()).containsExactly(1, 3);
        assertThat(second.replication().join()).extracting(PushOutcome::status)
                .containsExactly(PushStatus.FAILED, PushStatus.ACKED);
        assertThat(valueOn(3, "k")).isEqualTo("b");

        cluster.recover(1);
        assertThat(replicas.primary().nodeId()).isEqualTo(1);
        assertThat(replicas.service(2).isPrimary()).isFalse();
        assertThat(bus.query(ReplicationNodeService.MODULE, 2, 100)).extracting(event -> event.type())
                .contains("PRIMARY_STEPPED_DOWN");
        assertThat(cluster.nodes()).allSatisfy(node -> assertThat(replicas.service(node.id()).epoch()).isEqualTo(1));
    }

    @Test
    @DisplayName("anti-entropy and the stale injection go through the primary")
    void antiEntropyAndInjectStaleGoThroughPrimary() throws IOException {
        replicas.write("k1", "a", ConsistencyModel.SYNCHRONOUS);
        cluster.crash(3);
        replicas.write("k2", "b", ConsistencyModel.SYNCHRONOUS);
        cluster.recover(3);
        assertThat(valueOn(3, "k2")).isNull();

        AntiEntropyReport report = replicas.antiEntropy(3);
        PushOutcome stale = replicas.injectStale(2, "k1", "old");

        assertThat(report.merged().applied()).isEqualTo(1);
        assertThat(valueOn(3, "k2")).isEqualTo("b");
        assertThat(stale.result()).contains(ApplyResult.STALE);
        assertThat(valueOn(2, "k1")).isEqualTo("a");
        assertThatThrownBy(() -> replicas.injectStale(2, "missing", "old")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reads and dumps are TCP exchanges from the cluster-level client: the cluster clock advances, and a crashed replica refuses")
    void readsUseTheClusterClockAndCrashedReplicasRefuse() throws IOException {
        replicas.write("k", "v", ConsistencyModel.SYNCHRONOUS);
        long before = cluster.clusterClock().current();

        ReadReply reply = replicas.read(2, "k");

        assertThat(reply.nodeId()).isEqualTo(2);
        assertThat(cluster.clusterClock().current()).isGreaterThan(reply.lamportTime()).isGreaterThan(before);
        assertThat(replicas.dump(3).items()).containsOnlyKeys("k");

        cluster.crash(2);
        assertThatThrownBy(() -> replicas.read(2, "k")).isInstanceOf(ConnectException.class);
        assertThatThrownBy(() -> replicas.dump(2)).isInstanceOf(ConnectException.class);
    }
}
