package com.udcf.modules.replication;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    // ------------------------------------------------------------------ E5c: takeover

    private List<ClusterEvent> events(int nodeId, String type) {
        return bus.query(ReplicationNodeService.MODULE, nodeId, 5000).stream()
                .filter(event -> event.type().equals(type)).toList();
    }

    private Map<Integer, Map<String, DataItem>> liveStores() throws IOException {
        Map<Integer, Map<String, DataItem>> stores = new HashMap<>();
        for (ClusterNode node : cluster.nodes()) {
            if (node.isUp()) {
                stores.put(node.id(), replicas.dump(node.id()).items());
            }
        }
        return stores;
    }

    @Test
    @DisplayName("the first selection has nothing to catch up: no CATCH_UP, no push, one PRIMARY_SELECTED without a previous primary")
    void firstSelectionSkipsCatchUp() {
        replicas.write("k", "v", ConsistencyModel.SYNCHRONOUS);

        assertThat(replicas.lastTakeover()).hasValueSatisfying(report -> {
            assertThat(report.previousPrimaryId()).isEmpty();
            assertThat(report.newPrimaryId()).isEqualTo(1);
            assertThat(report.catchUps()).isEmpty();
            assertThat(report.pushes()).isEmpty();
        });
        assertThat(events(1, "CATCH_UP")).isEmpty();
        assertThat(events(1, "ANTI_ENTROPY")).isEmpty();
        assertThat(events(1, "PRIMARY_SELECTED")).singleElement()
                .satisfies(event -> assertThat(event.peerId()).isNull());
        assertThat(replicas.currentPrimaryId()).contains(1);
    }

    @Test
    @DisplayName("a recovered node 1 catches up from every live peer before it takes over, so it holds node 2's writes")
    void recoveredPrimaryCatchesUpBeforeTakingOver() throws IOException {
        replicas.write("k", "a", ConsistencyModel.SYNCHRONOUS);
        cluster.crash(1);
        replicas.write("k", "b", ConsistencyModel.SYNCHRONOUS);
        replicas.write("only-on-2-and-3", "x", ConsistencyModel.SYNCHRONOUS);
        cluster.recover(1);
        assertThat(replicas.takeoverPending()).isTrue();

        ReplicationNodeService primary = replicas.primary();

        assertThat(primary.nodeId()).isEqualTo(1);
        assertThat(primary.get("k")).hasValueSatisfying(item -> assertThat(item.value()).isEqualTo("b"));
        assertThat(primary.get("only-on-2-and-3")).isPresent();
        assertThat(replicas.lastTakeover()).hasValueSatisfying(report -> {
            assertThat(report.previousPrimaryId()).contains(2);
            assertThat(report.catchUps()).extracting(CatchUpReport::sourceNodeId).containsExactly(2, 3);
            assertThat(report.catchUps()).allMatch(CatchUpReport::completed);
            assertThat(report.appliedFromCatchUp()).isEqualTo(2);
            assertThat(report.pushes()).extracting(AntiEntropyReport::targetNodeId).containsExactly(2, 3);
        });
        assertThat(events(1, "PRIMARY_SELECTED")).last().satisfies(event -> {
            assertThat(event.peerId()).isEqualTo(2);
            assertThat(event.data()).containsEntry("previousPrimaryId", 2).containsEntry("appliedFromCatchUp", 2);
        });
        assertThat(replicas.takeoverPending()).isFalse();
        assertThat(ConsistencyCheck.compare(1, liveStores()).consistent()).isTrue();
    }

    @Test
    @DisplayName("the takeover push repairs a live backup that missed the previous primary's writes")
    void takeoverPushesToLiveBackups() throws IOException {
        replicas.write("k1", "a", ConsistencyModel.SYNCHRONOUS);
        cluster.crash(1);
        cluster.crash(3);
        replicas.write("k2", "b", ConsistencyModel.SYNCHRONOUS);   // node 2 takes over; 3 misses k2
        cluster.recover(3);
        cluster.recover(1);
        assertThat(replicas.dump(3).items()).doesNotContainKey("k2");

        replicas.primary();

        assertThat(replicas.dump(3).items()).containsKey("k2");
        assertThat(replicas.lastTakeover()).hasValueSatisfying(report -> assertThat(report.pushes())
                .filteredOn(push -> push.targetNodeId() == 3).singleElement()
                .satisfies(push -> assertThat(push.merged().applied()).isEqualTo(1)));
        assertThat(ConsistencyCheck.compare(1, liveStores()).consistent()).isTrue();
    }

    @Test
    @DisplayName("after a takeover, a write to a key that already exists is APPLIED on every live replica (never STALE), and they end consistent")
    void writeAfterTakeoverBeatsEveryCaughtUpItem() throws IOException {
        replicas.write("k", "from-1", ConsistencyModel.SYNCHRONOUS);
        cluster.crash(1);
        // Node 2 writes many versions, so its clock (and its items' Lamport times) run far ahead of node 1's.
        for (int i = 0; i < 30; i++) {
            replicas.write("k", "from-2-" + i, ConsistencyModel.SYNCHRONOUS);
        }
        long highestOnTwo = replicas.dump(2).items().get("k").lamportTime();
        cluster.recover(1);
        assertThat(cluster.node(1).clock().current()).isLessThan(highestOnTwo);

        WriteResult result = replicas.write("k", "from-1-after-takeover", ConsistencyModel.SYNCHRONOUS);

        assertThat(result.item().originNode()).isEqualTo(1);
        assertThat(result.item().lamportTime()).isGreaterThan(highestOnTwo);
        assertThat(result.localResult()).isEqualTo(ApplyResult.APPLIED);
        assertThat(result.replication().join()).extracting(PushOutcome::result)
                .containsOnly(Optional.of(ApplyResult.APPLIED));
        assertThat(liveStores().values()).allSatisfy(store -> assertThat(store.get("k").value())
                .isEqualTo("from-1-after-takeover"));
        assertThat(ConsistencyCheck.compare(1, liveStores()).consistent()).isTrue();
    }

    @Test
    @DisplayName("read-only views never start a service, change a role or take over")
    void readOnlyViewsNeverTakeOver() throws IOException {
        assertThat(replicas.selectedPrimaryId()).contains(1);
        assertThat(replicas.currentPrimaryId()).isEmpty();
        assertThat(replicas.takeoverPending()).isFalse();
        assertThat(cluster.nodes()).allSatisfy(node -> assertThat(node.runningServices()).isEmpty());

        replicas.write("k", "v", ConsistencyModel.SYNCHRONOUS);
        cluster.crash(1);
        assertThat(replicas.selectedPrimaryId()).contains(2);
        assertThat(replicas.takeoverPending()).isTrue();
        replicas.read(2, "k");
        replicas.dump(3);
        replicas.lastTakeover();

        assertThat(replicas.currentPrimaryId()).contains(1);
        assertThat(replicas.takeoverPending()).isTrue();
        assertThat(replicas.service(2).isPrimary()).isFalse();
        assertThat(events(2, "PRIMARY_SELECTED")).isEmpty();
        cluster.crash(2);
        cluster.crash(3);
        assertThat(replicas.selectedPrimaryId()).isEmpty();
        assertThat(replicas.takeoverPending()).isFalse();
    }

    @Test
    @DisplayName("reset forgets the node made primary and the latest selection; the next write is a first selection again")
    void resetForgetsPrimary() {
        replicas.write("k", "v", ConsistencyModel.SYNCHRONOUS);
        cluster.crash(1);
        assertThat(replicas.takeoverPending()).isTrue();

        replicas.reset();

        assertThat(replicas.currentPrimaryId()).isEmpty();
        assertThat(replicas.lastTakeover()).isEmpty();
        assertThat(replicas.takeoverPending()).isFalse();
        replicas.write("k2", "v", ConsistencyModel.SYNCHRONOUS);
        assertThat(replicas.lastTakeover()).hasValueSatisfying(report -> {
            assertThat(report.previousPrimaryId()).isEmpty();
            assertThat(report.catchUps()).isEmpty();
        });
    }
}
