package com.udcf.modules.election;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.cluster.NodeRole;
import com.udcf.core.cluster.UnknownNodeException;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.core.events.EventProperties;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.election.dto.ConsensusDto;
import com.udcf.modules.election.dto.ElectionNodeDto;
import com.udcf.modules.election.dto.ElectionOverviewDto;
import com.udcf.modules.election.dto.ElectionRoundDto;
import com.udcf.modules.election.dto.StartElectionCommand;
import com.udcf.web.InvalidParameterException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;
import static org.awaitility.Awaitility.await;

/**
 * Experiment 4 on a standalone five-node cluster with real 127.0.0.1 UDP sockets: lazy start,
 * manual Bully and Ring, the LEADER role, the consensus check, automatic re-election after the
 * leader crashes, recovery, reset and the round timeout.
 *
 * <p>Test ports (Track B block, below 32768, outside the Linux and Windows ephemeral ranges):
 * cluster bases 26300 to 26350, so the election ports are 26321 to 26325. Nothing else binds.</p>
 */
class ElectionModuleTest {

    private static final ClusterProperties CLUSTER = new ClusterProperties(5,
            List.of(FAST, MEDIUM, SLOW, MEDIUM, FAST),
            new ClusterProperties.Ports(26300, 26310, 26320, 26330, 26340, 26350));
    private static final ElectionProperties PROPERTIES = new ElectionProperties(400, 1500, 150, 3000, 100, 1500, 10000);

    private ClusterEventBus bus;
    private Cluster cluster;
    private SimpleMeterRegistry registry;
    private ElectionModule module;
    private final AtomicLong nanoOffset = new AtomicLong();
    /** When armed, the first NODE_RECOVERED holds the event dispatcher until released (registered before the module). */
    private final AtomicReference<CountDownLatch> dispatcherGate = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), Clock.systemUTC());
        bus.subscribe(event -> {
            CountDownLatch gate = dispatcherGate.get();
            if (gate != null && event.type().equals("NODE_RECOVERED") && dispatcherGate.compareAndSet(gate, null)) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        cluster = new Cluster(CLUSTER, bus);
        registry = new SimpleMeterRegistry();
        module = new ElectionModule(cluster, PROPERTIES, bus, registry,
                () -> System.nanoTime() + nanoOffset.get(), Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        module.close();
        cluster.close();
        bus.close();
    }

    private static ConditionFactory within() {
        return await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50));
    }

    private ElectionRoundDto start(ElectionAlgorithm algorithm, Integer nodeId) {
        return module.startElection(new StartElectionCommand(algorithm, nodeId));
    }

    /** The cluster leader is {@code leader} and the last round ended ELECTED with it. */
    private boolean electedRoundWith(int leader) {
        ElectionOverviewDto overview = module.overview();
        return cluster.leaderId().equals(java.util.Optional.of(leader)) && overview.currentRound() == null
                && overview.lastRound() != null && "ELECTED".equals(overview.lastRound().outcome())
                && Objects.equals(overview.lastRound().leaderId(), leader);
    }

    private void electFiveFromNodeOne() {
        start(ElectionAlgorithm.BULLY, 1);
        within().until(() -> electedRoundWith(5));
    }

    private List<ClusterEvent> events(String type) {
        return bus.query(ElectionModule.ID, null, 5000).stream().filter(e -> e.type().equals(type)).toList();
    }

    private long roundsStarted(String trigger) {
        return events(ElectionModule.ROUND_STARTED).stream().filter(e -> trigger.equals(e.data().get("trigger"))).count();
    }

    /** Waits until the event dispatcher has delivered everything published so far. */
    private void flushBus() {
        AtomicBoolean seen = new AtomicBoolean();
        try (ClusterEventBus.Subscription ignored = bus.subscribe(e -> {
            if (e.type().equals("TEST_FLUSH")) {
                seen.set(true);
            }
        })) {
            bus.publish(EventDraft.of("test", 0, "TEST_FLUSH", 0));
            within().untilTrue(seen);
        }
    }

    @Test
    @DisplayName("before the first election the module is IDLE and reading it starts no service")
    void idleBeforeFirstElectionAndOverviewStartsNothing() {
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
        ElectionOverviewDto overview = module.overview();
        assertThat(overview.leaderId()).isNull();
        assertThat(overview.servicesStarted()).isFalse();
        assertThat(overview.nodes()).extracting(ElectionNodeDto::serviceRunning).containsOnly(false);
        assertThat(overview.nodes()).extracting(ElectionNodeDto::coordinatorId).containsOnlyNulls();
        assertThat(overview.nodes()).extracting(ElectionNodeDto::port).containsExactly(26321, 26322, 26323, 26324, 26325);
        assertThat(overview.consensus().passed()).isFalse();
        assertThat(overview.currentRound()).isNull();
        assertThat(overview.lastRound()).isNull();
        assertThat(module.consensus().disagreeingNodes()).containsExactly(1, 2, 3, 4, 5);
        assertThat(cluster.nodes()).extracting(ClusterNode::runningServices).containsOnly(List.of());
    }

    @Test
    @DisplayName("Bully from node 1 starts every service, elects node 5, gives it the LEADER role and records the metrics")
    void manualBullyElectsHighestAssignsLeaderAndRecordsMetrics() {
        ElectionRoundDto started = start(ElectionAlgorithm.BULLY, 1);
        assertThat(started.algorithm()).isEqualTo("BULLY");
        assertThat(started.trigger()).isEqualTo("MANUAL");
        assertThat(started.initiatorNodeId()).isEqualTo(1);
        assertThat(started.outcome()).isEqualTo("IN_PROGRESS");
        assertThat(started.leaderId()).isNull();
        assertThat(started.durationMillis()).isNull();
        assertThat(cluster.nodes()).extracting(ClusterNode::runningServices).containsOnly(List.of("election"));

        within().until(() -> electedRoundWith(5));
        assertThat(cluster.node(5).roles()).containsExactly(NodeRole.LEADER);
        ElectionOverviewDto overview = module.overview();
        assertThat(overview.leaderId()).isEqualTo(5);
        assertThat(overview.servicesStarted()).isTrue();
        assertThat(overview.lastRound().durationMillis()).isPositive();
        assertThat(overview.consensus().passed()).isTrue();
        assertThat(module.status()).isEqualTo(ModuleStatus.RUNNING);
        assertThat(registry.get("distributed_leader_elections_total")
                .tags("node_id", "5", "algorithm", "BULLY", "trigger", "MANUAL").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("distributed_election_duration")
                .tags("node_id", "1", "algorithm", "BULLY", "trigger", "MANUAL").timer().count()).isEqualTo(1);
        assertThat(events(ElectionModule.ROUND_STARTED)).hasSize(1);
        assertThat(events(ElectionModule.ROUND_FINISHED)).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("outcome", "ELECTED").containsEntry("leaderId", 5));
    }

    @Test
    @DisplayName("a second start while a round is open is refused as busy")
    void secondStartWhileRoundOpenIsBusy() {
        cluster.crash(2);
        cluster.crash(3);
        start(ElectionAlgorithm.RING, 1);   // two dead successors: at least two 150 ms probe timeouts
        assertThat(module.status()).isEqualTo(ModuleStatus.BUSY);
        assertThatThrownBy(() -> start(ElectionAlgorithm.BULLY, 4)).isInstanceOf(ModuleBusyException.class);
        within().until(() -> electedRoundWith(5));
    }

    @Test
    @DisplayName("crashing the leader removes its role at once; the detectors start Bully and node 4 is elected with no request")
    void crashingLeaderTriggersAutomaticReElection() {
        electFiveFromNodeOne();
        cluster.crash(5);
        assertThat(cluster.leaderId()).isEmpty();
        within().until(() -> electedRoundWith(4));
        ElectionRoundDto last = module.overview().lastRound();
        assertThat(last.trigger()).isEqualTo("LEADER_FAILURE");
        assertThat(last.algorithm()).isEqualTo("BULLY");
        assertThat(roundsStarted("LEADER_FAILURE")).isEqualTo(1);
        assertThat(registry.get("distributed_leader_elections_total")
                .tags("node_id", "4", "algorithm", "BULLY", "trigger", "LEADER_FAILURE").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Ring from node 2 skips crashed node 3 and elects node 5")
    void ringSkipsDeadNodeAndElectsHighest() {
        cluster.crash(3);
        start(ElectionAlgorithm.RING, 2);
        within().until(() -> electedRoundWith(5));
        assertThat(module.overview().lastRound().algorithm()).isEqualTo("RING");
        assertThat(events("DEAD_NODE_SKIPPED")).anySatisfy(e -> {
            assertThat(e.nodeId()).isEqualTo(2);
            assertThat(e.peerId()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("a recovered node 5 takes leadership back in a RECOVERY round")
    void recoveredHighestNodeReclaimsLeadership() {
        electFiveFromNodeOne();
        cluster.crash(5);
        within().until(() -> electedRoundWith(4));
        cluster.recover(5);
        within().until(() -> electedRoundWith(5));
        assertThat(module.overview().lastRound().trigger()).isEqualTo("RECOVERY");
    }

    @Test
    @DisplayName("a node that was down when the services started joins when it recovers, and reclaims leadership")
    void nodeCrashedBeforeFirstElectionJoinsWhenRecovered() {
        cluster.crash(5);
        start(ElectionAlgorithm.BULLY, 1);
        within().until(() -> electedRoundWith(4));
        assertThat(ElectionNodeService.find(cluster.node(5))).isEmpty();
        cluster.recover(5);
        within().until(() -> electedRoundWith(5));
        assertThat(ElectionNodeService.find(cluster.node(5))).hasValueSatisfying(s -> assertThat(s.isRunning()).isTrue());
        assertThat(module.overview().lastRound().trigger()).isEqualTo("RECOVERY");
    }

    @Test
    @DisplayName("the consensus check passes on a live leader and fails once that leader has crashed")
    void consensusPassesThenFailsWhenLeaderCrashes() {
        electFiveFromNodeOne();
        ConsensusDto agreed = module.consensus();
        assertThat(agreed.reached()).isTrue();
        assertThat(agreed.coordinatorId()).isEqualTo(5);
        assertThat(agreed.coordinatorAlive()).isTrue();
        assertThat(agreed.passed()).isTrue();
        assertThat(agreed.disagreeingNodes()).isEmpty();

        cluster.crash(5);   // detection takes 1500 ms: the live nodes still name node 5 for now
        ConsensusDto deadLeader = module.consensus();
        assertThat(deadLeader.reached()).isTrue();
        assertThat(deadLeader.coordinatorId()).isEqualTo(5);
        assertThat(deadLeader.coordinatorAlive()).isFalse();
        assertThat(deadLeader.passed()).isFalse();
        within().until(() -> electedRoundWith(4));
    }

    @Test
    @DisplayName("a start from a crashed node, an unknown node or with no algorithm fails and leaves no round open")
    void startFromCrashedOrUnknownNodeFails() {
        cluster.crash(2);
        assertThatThrownBy(() -> start(ElectionAlgorithm.BULLY, 2)).isInstanceOf(NodeDownException.class);
        assertThatThrownBy(() -> start(ElectionAlgorithm.BULLY, 9)).isInstanceOf(UnknownNodeException.class);
        assertThatThrownBy(() -> start(null, 1)).isInstanceOf(InvalidParameterException.class);
        assertThatThrownBy(() -> module.startElection(new StartElectionCommand(ElectionAlgorithm.RING, null)))
                .isInstanceOf(InvalidParameterException.class);
        assertThat(module.overview().currentRound()).isNull();
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
    }

    @Test
    @DisplayName("four detectors suspecting the same leader at once open exactly one LEADER_FAILURE round")
    void concurrentLeaderSuspicionsOpenExactlyOneRound() throws InterruptedException {
        electFiveFromNodeOne();
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);
        for (int nodeId = 1; nodeId <= 4; nodeId++) {
            int suspecting = nodeId;
            Thread caller = new Thread(() -> {
                try {
                    go.await();
                    module.onPeerSuspected(suspecting, 5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "test-suspicion-" + nodeId);
            caller.start();
        }
        go.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        within().until(() -> module.overview().currentRound() == null);
        assertThat(roundsStarted("LEADER_FAILURE")).isEqualTo(1);
        assertThat(cluster.leaderId()).contains(5);
    }

    @Test
    @DisplayName("reset removes the leader, the rounds and every coordinator, keeps services running, and ignores earlier recoveries")
    void resetClearsLeaderRoundAndCoordinatorsAndIgnoresEarlierRecoveries() {
        cluster.crash(5);
        start(ElectionAlgorithm.BULLY, 1);
        within().until(() -> electedRoundWith(4));

        // As ClusterResetService does: recover every node, then reset the module. The NODE_RECOVERED
        // for node 5 is held on the dispatcher until after the reset.
        CountDownLatch gate = new CountDownLatch(1);
        dispatcherGate.set(gate);
        cluster.recoverAll();
        module.reset();
        gate.countDown();
        flushBus();

        assertThat(cluster.leaderId()).isEmpty();
        ElectionOverviewDto overview = module.overview();
        assertThat(overview.currentRound()).isNull();
        assertThat(overview.lastRound()).isNull();
        assertThat(overview.nodes()).filteredOn(n -> n.nodeId() <= 4)
                .allSatisfy(n -> {
                    assertThat(n.serviceRunning()).isTrue();
                    assertThat(n.coordinatorId()).isNull();
                    assertThat(n.suspectedPeers()).isEmpty();
                });
        assertThat(ElectionNodeService.find(cluster.node(5))).isEmpty();   // the old recovery was ignored
        assertThat(module.status()).isEqualTo(ModuleStatus.RUNNING);

        start(ElectionAlgorithm.BULLY, 1);   // a fresh election starts node 5's service too, so node 5 wins
        within().until(() -> electedRoundWith(5));
    }

    @Test
    @DisplayName("the overview reports per node the wins, the timed rounds started there and their mean, from the meters")
    void overviewReportsPerNodeWinsAndMeanDuration() {
        assertThat(module.overview().nodes()).allSatisfy(n -> {
            assertThat(n.electionsWon()).isZero();
            assertThat(n.roundsTimed()).isZero();
            assertThat(n.meanDurationMillis()).isNull();
        });
        electFiveFromNodeOne();
        ElectionOverviewDto overview = module.overview();
        ElectionNodeDto one = overview.nodes().get(0);
        ElectionNodeDto five = overview.nodes().get(4);
        assertThat(five.electionsWon()).isEqualTo(1);
        assertThat(five.roundsTimed()).isZero();
        assertThat(five.meanDurationMillis()).isNull();
        assertThat(one.electionsWon()).isZero();
        assertThat(one.roundsTimed()).isEqualTo(1);
        assertThat(one.meanDurationMillis()).isCloseTo(overview.lastRound().durationMillis(), offset(0.001));
    }

    @Test
    @DisplayName("a round with no agreement times out: no leader, no duration, no metric")
    void overdueRoundTimesOutWithoutMetrics() {
        for (int nodeId = 2; nodeId <= 5; nodeId++) {
            cluster.crash(nodeId);
        }
        start(ElectionAlgorithm.RING, 1);   // ring of one live node: never elects anyone (E4a)
        assertThat(module.status()).isEqualTo(ModuleStatus.BUSY);
        nanoOffset.set(TimeUnit.MILLISECONDS.toNanos(10_001));
        assertThat(module.status()).isEqualTo(ModuleStatus.RUNNING);
        ElectionRoundDto last = module.overview().lastRound();
        assertThat(last.outcome()).isEqualTo("TIMED_OUT");
        assertThat(last.leaderId()).isNull();
        assertThat(last.durationMillis()).isNull();
        assertThat(events(ElectionModule.ROUND_FINISHED)).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("outcome", "TIMED_OUT").containsEntry("durationMillis", null));
        assertThat(registry.getMeters()).isEmpty();
        assertThat(cluster.leaderId()).isEmpty();
    }
}
