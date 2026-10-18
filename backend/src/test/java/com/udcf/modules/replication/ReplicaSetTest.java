package com.udcf.modules.replication;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.LeaderElector;
import com.udcf.core.cluster.NodeRole;
import com.udcf.core.cluster.RoleProvider;
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
import java.util.OptionalLong;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The replica-set facade on a standalone three-node cluster with real sockets.
 *
 * <p>No election module: the {@link RoleProvider} has no elector, so the tests play Experiment 4
 * with {@link Cluster#assignLeader} (node 1 leads from the start) and "no leader" fails at once.</p>
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
    private RoleProvider roles;
    private ReplicaSet replicas;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), Clock.systemUTC());
        cluster = new Cluster(CLUSTER, bus);
        roles = new RoleProvider(cluster, (LeaderElector) null);
        replicas = new ReplicaSet(cluster, PROPERTIES, bus, roles);
        cluster.assignLeader(1);
    }

    @AfterEach
    void tearDown() {
        roles.close();
        cluster.close();
        bus.close();
    }

    private String valueOn(int nodeId, String key) throws IOException {
        return replicas.read(nodeId, key).item().map(DataItem::value).orElse(null);
    }

    /** A synchronous write through the leader, as an Experiment 5 action does. */
    private WriteResult write(String key, String value) {
        return replicas.write(replicas.leader(), key, value, ConsistencyModel.SYNCHRONOUS);
    }

    private ReplicationNodeService primary() {
        return replicas.primary(replicas.leader());
    }

    @Test
    @DisplayName("the primary is the elected leader; with no leader there is none, and an action gets NoLeaderException")
    void primaryIsTheLeader() {
        assertThat(replicas.selectedPrimaryId()).contains(1);
        assertThat(replicas.leader()).isEqualTo(1);
        cluster.assignLeader(3);
        assertThat(replicas.selectedPrimaryId()).contains(3);

        cluster.crash(3);   // a crashed node never leads
        assertThat(replicas.selectedPrimaryId()).isEmpty();
        assertThatThrownBy(replicas::leader).isInstanceOf(NoLeaderException.class)
                .hasMessageStartingWith("No leader could be elected, so Experiment 5 has no primary");
    }

    @Test
    @DisplayName("every other node is a backup, crashed ones included")
    void backupsIncludeCrashed() {
        cluster.crash(3);
        assertThat(write("a", "1").backupIds()).containsExactly(2, 3);
        cluster.crash(1);
        cluster.assignLeader(2);
        assertThat(write("b", "2").backupIds()).containsExactly(1, 3);
    }

    @Test
    @DisplayName("a write goes through the leader at epoch 1, starts the service on every live backup and publishes PRIMARY and BACKUP")
    void writeRoutesThroughPrimaryAndStartsBackups() throws IOException {
        WriteResult result = write("k", "v");

        assertThat(result.item().originNode()).isEqualTo(1);
        assertThat(result.item().epoch()).isEqualTo(1);
        assertThat(result.backupIds()).containsExactly(2, 3);
        assertThat(replicas.service(1).isPrimary()).isTrue();
        assertThat(cluster.nodes()).allSatisfy(node -> assertThat(node.runningServices())
                .contains(ReplicationNodeService.NAME));
        assertThat(valueOn(2, "k")).isEqualTo("v");
        assertThat(valueOn(3, "k")).isEqualTo("v");
        assertThat(roles.primaryId()).contains(1);
        assertThat(cluster.node(2).hasRole(NodeRole.BACKUP)).isTrue();
        assertThat(cluster.node(3).hasRole(NodeRole.BACKUP)).isTrue();
    }

    @Test
    @DisplayName("the primary follows the leader: node 2 after node 1 crashes; node 1 again when it recovers and leads, node 2 steps down")
    void primaryFollowsTheLeader() throws IOException {
        write("k", "a");
        cluster.crash(1);
        cluster.assignLeader(2);

        WriteResult second = write("k", "b");

        assertThat(second.item().originNode()).isEqualTo(2);
        assertThat(second.backupIds()).containsExactly(1, 3);
        assertThat(second.replication().join()).extracting(PushOutcome::status)
                .containsExactly(PushStatus.FAILED, PushStatus.ACKED);
        assertThat(valueOn(3, "k")).isEqualTo("b");
        assertThat(roles.primaryId()).contains(2);

        cluster.recover(1);
        cluster.assignLeader(1);
        assertThat(primary().nodeId()).isEqualTo(1);
        assertThat(replicas.service(2).isPrimary()).isFalse();
        assertThat(bus.query(ReplicationNodeService.MODULE, 2, 100)).extracting(event -> event.type())
                .contains("PRIMARY_STEPPED_DOWN");
        assertThat(cluster.nodes()).allSatisfy(node -> assertThat(replicas.service(node.id()).epoch()).isEqualTo(1));
        assertThat(roles.primaryId()).contains(1);
        assertThat(cluster.node(2).hasRole(NodeRole.BACKUP)).isTrue();
    }

    @Test
    @DisplayName("anti-entropy and the stale injection go through the primary")
    void antiEntropyAndInjectStaleGoThroughPrimary() throws IOException {
        write("k1", "a");
        cluster.crash(3);
        write("k2", "b");
        cluster.recover(3);
        assertThat(valueOn(3, "k2")).isNull();

        AntiEntropyReport report = replicas.antiEntropy(1, 3);
        PushOutcome stale = replicas.injectStale(1, 2, "k1", "old");

        assertThat(report.merged().applied()).isEqualTo(1);
        assertThat(valueOn(3, "k2")).isEqualTo("b");
        assertThat(stale.result()).contains(ApplyResult.STALE);
        assertThat(valueOn(2, "k1")).isEqualTo("a");
        assertThatThrownBy(() -> replicas.injectStale(1, 2, "missing", "old"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a recovered backup receives the next write through its reopened service (no takeover needed)")
    void recoveredBackupGetsTheNextWrite() throws IOException {
        write("before", "a");
        cluster.crash(3);
        write("missed", "b");
        cluster.recover(3);

        WriteResult after = write("after", "c");

        assertThat(after.replication().join()).extracting(PushOutcome::backupId, PushOutcome::status)
                .contains(org.assertj.core.groups.Tuple.tuple(3, PushStatus.ACKED));
        assertThat(valueOn(3, "after")).isEqualTo("c");
        assertThat(valueOn(3, "missed")).isNull();   // anti-entropy repairs what it missed while down
    }

    @Test
    @DisplayName("reads and dumps are TCP exchanges from the cluster-level client: the cluster clock advances, and a crashed replica refuses")
    void readsUseTheClusterClockAndCrashedReplicasRefuse() throws IOException {
        write("k", "v");
        long before = cluster.clusterClock().current();

        ReadReply reply = replicas.read(2, "k");

        assertThat(reply.nodeId()).isEqualTo(2);
        assertThat(cluster.clusterClock().current()).isGreaterThan(reply.lamportTime()).isGreaterThan(before);
        assertThat(replicas.dump(3).items()).containsOnlyKeys("k");

        cluster.crash(2);
        assertThatThrownBy(() -> replicas.read(2, "k")).isInstanceOf(ConnectException.class);
        assertThatThrownBy(() -> replicas.dump(2)).isInstanceOf(ConnectException.class);
    }

    // ------------------------------------------------------------------ 9A.3: the leader and Experiment 8's epochs

    @Test
    @DisplayName("the leader already acts as primary at a higher epoch (Experiment 8): nothing moves, no event, no role publish")
    void leaderAlreadyPrimaryChangesNothing() throws IOException {
        replicas.service(1);
        replicas.service(2);
        replicas.service(3).becomePrimary(2);   // as Experiment 8 promotes the elected leader
        cluster.assignLeader(3);

        WriteResult result = write("k", "v");

        assertThat(result.item().originNode()).isEqualTo(3);
        assertThat(result.item().epoch()).isEqualTo(2);
        assertThat(replicas.service(3).primaryEpoch()).isEqualTo(OptionalLong.of(2));
        assertThat(replicas.currentPrimaryId()).contains(3);
        assertThat(replicas.lastTakeover()).isEmpty();
        assertThat(bus.query(ReplicationNodeService.MODULE, null, 500)).extracting(ClusterEvent::type)
                .doesNotContain("PRIMARY_SELECTED", "PRIMARY_STEPPED_DOWN");
        assertThat(roles.primaryId()).isEmpty();   // Experiment 8 owns the roles it appointed
        assertThat(valueOn(1, "k")).isEqualTo("v");
    }

    @Test
    @DisplayName("epochs never go down: an old primary at epoch 3 steps down and the leader (epoch 1) acts at epoch 3")
    void takeoverNeverLowersTheEpoch() {
        ReplicationNodeService leader = replicas.service(1);
        replicas.service(2).becomePrimary(3);
        assertThat(leader.epoch()).isEqualTo(1);

        ReplicationNodeService primary = primary();

        assertThat(primary.nodeId()).isEqualTo(1);
        assertThat(primary.primaryEpoch().getAsLong()).isGreaterThanOrEqualTo(3);
        assertThat(replicas.service(2).isPrimary()).isFalse();
        assertThat(roles.primaryId()).contains(1);
    }

    @Test
    @DisplayName("a recovered old leader that still believes it is primary does not stand while another node serves at its epoch")
    void staleRecoveredPrimaryTakesOverProperly() {
        write("k", "a");
        cluster.crash(1);
        cluster.assignLeader(2);
        write("k", "b");
        cluster.recover(1);
        cluster.assignLeader(1);
        assertThat(replicas.service(1).isPrimary()).isTrue();   // the role survived the crash

        ReplicationNodeService primary = primary();

        assertThat(primary.get("k")).hasValueSatisfying(item -> assertThat(item.value()).isEqualTo("b"));
        assertThat(replicas.service(2).isPrimary()).isFalse();
        assertThat(replicas.lastTakeover()).hasValueSatisfying(report -> assertThat(report.previousPrimaryId()).contains(2));
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
        write("k", "v");

        assertThat(replicas.lastTakeover()).hasValueSatisfying(report -> {
            assertThat(report.previousPrimaryId()).isEmpty();
            assertThat(report.newPrimaryId()).isEqualTo(1);
            assertThat(report.catchUps()).isEmpty();
            assertThat(report.pushes()).isEmpty();
        });
        assertThat(events(1, "CATCH_UP")).isEmpty();
        assertThat(events(1, "ANTI_ENTROPY")).isEmpty();
        assertThat(events(1, "PRIMARY_SELECTED")).singleElement().satisfies(event -> {
            assertThat(event.peerId()).isNull();
            assertThat(event.data()).containsEntry("reason", "elected leader");
        });
        assertThat(replicas.currentPrimaryId()).contains(1);
    }

    @Test
    @DisplayName("a recovered node 1 that leads again catches up from every live peer before it takes over, so it holds node 2's writes")
    void recoveredPrimaryCatchesUpBeforeTakingOver() throws IOException {
        write("k", "a");
        cluster.crash(1);
        cluster.assignLeader(2);
        write("k", "b");
        write("only-on-2-and-3", "x");
        cluster.recover(1);
        cluster.assignLeader(1);
        assertThat(replicas.takeoverPending()).isTrue();

        ReplicationNodeService primary = primary();

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
        write("k1", "a");
        cluster.crash(1);
        cluster.crash(3);
        cluster.assignLeader(2);
        write("k2", "b");   // node 2 takes over; 3 misses k2
        cluster.recover(3);
        cluster.recover(1);
        cluster.assignLeader(1);
        assertThat(replicas.dump(3).items()).doesNotContainKey("k2");

        primary();

        assertThat(replicas.dump(3).items()).containsKey("k2");
        assertThat(replicas.lastTakeover()).hasValueSatisfying(report -> assertThat(report.pushes())
                .filteredOn(push -> push.targetNodeId() == 3).singleElement()
                .satisfies(push -> assertThat(push.merged().applied()).isEqualTo(1)));
        assertThat(ConsistencyCheck.compare(1, liveStores()).consistent()).isTrue();
    }

    @Test
    @DisplayName("after a takeover, a write to a key that already exists is APPLIED on every live replica (never STALE), and they end consistent")
    void writeAfterTakeoverBeatsEveryCaughtUpItem() throws IOException {
        write("k", "from-1");
        cluster.crash(1);
        cluster.assignLeader(2);
        // Node 2 writes many versions, so its clock (and its items' Lamport times) run far ahead of node 1's.
        for (int i = 0; i < 30; i++) {
            write("k", "from-2-" + i);
        }
        long highestOnTwo = replicas.dump(2).items().get("k").lamportTime();
        cluster.recover(1);
        cluster.assignLeader(1);
        assertThat(cluster.node(1).clock().current()).isLessThan(highestOnTwo);

        WriteResult result = write("k", "from-1-after-takeover");

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
    @DisplayName("read-only views never start a service, change a role, take over or elect")
    void readOnlyViewsNeverTakeOver() throws IOException {
        assertThat(replicas.selectedPrimaryId()).contains(1);
        assertThat(replicas.currentPrimaryId()).isEmpty();
        assertThat(replicas.takeoverPending()).isFalse();
        assertThat(cluster.nodes()).allSatisfy(node -> assertThat(node.runningServices()).isEmpty());

        write("k", "v");
        cluster.crash(1);
        assertThat(replicas.selectedPrimaryId()).isEmpty();
        assertThat(replicas.takeoverPending()).isFalse();
        cluster.assignLeader(2);
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
        write("k", "v");
        cluster.crash(1);
        cluster.assignLeader(2);
        assertThat(replicas.takeoverPending()).isTrue();

        replicas.reset();

        assertThat(replicas.currentPrimaryId()).isEmpty();
        assertThat(replicas.lastTakeover()).isEmpty();
        assertThat(replicas.takeoverPending()).isFalse();
        write("k2", "v");
        assertThat(replicas.lastTakeover()).hasValueSatisfying(report -> {
            assertThat(report.previousPrimaryId()).isEmpty();
            assertThat(report.catchUps()).isEmpty();
        });
    }
}
