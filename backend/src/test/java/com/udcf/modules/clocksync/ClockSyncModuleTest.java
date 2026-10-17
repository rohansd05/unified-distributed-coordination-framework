package com.udcf.modules.clocksync;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.LeaderElector;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.cluster.RoleProvider;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.clocksync.dto.BerkeleyRoundAcceptedDto;
import com.udcf.modules.clocksync.dto.BerkeleyRoundCommand;
import com.udcf.modules.clocksync.dto.BerkeleyRoundDto;
import com.udcf.modules.clocksync.dto.CausalVerificationDto;
import com.udcf.modules.clocksync.dto.ClockSyncOverviewDto;
import com.udcf.modules.clocksync.dto.LocalEventCommand;
import com.udcf.modules.clocksync.dto.LocalEventResult;
import com.udcf.modules.clocksync.dto.NodeDriftResponseDto;
import com.udcf.modules.clocksync.dto.NodeDriftUpdateCommand;
import com.udcf.modules.clocksync.dto.SendLamportResult;
import com.udcf.modules.clocksync.dto.SendMessageCommand;
import com.udcf.modules.clocksync.dto.TimelineResponseDto;
import com.udcf.web.InvalidParameterException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for {@link ClockSyncModule} using standalone cluster and real UDP sockets
 * on reserved port range 24100-24899.
 */
class ClockSyncModuleTest {

    private static final ClusterProperties CLUSTER_PROPS = new ClusterProperties(
            3,
            List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(24100, 24400, 24300, 24500, 24700, 24800)
    );

    private static final ClockSyncProperties PROPERTIES = new ClockSyncProperties(
            500L,
            300L,
            5,
            30,
            4,
            20,
            2000,
            Map.of(
                    1, new ClockSyncProperties.NodeDriftConfig(0L, 0.0),
                    2, new ClockSyncProperties.NodeDriftConfig(50L, 1.0),
                    3, new ClockSyncProperties.NodeDriftConfig(-30L, -0.5)
            )
    );

    private Cluster cluster;
    private ClusterEventBus bus;
    private Clock wallClock;
    private ClockSyncModule module;
    private RoleProvider roles;
    private final AtomicInteger elections = new AtomicInteger();

    @BeforeEach
    void setUp() {
        wallClock = Clock.systemUTC();
        bus = new ClusterEventBus(new EventProperties(2000, 2000), wallClock);
        cluster = new Cluster(CLUSTER_PROPS, bus);
        // test elector, not Bully: the real leader is proven by the *LeaderIntegrationTest classes.
        // It makes the highest live node leader at once, as Bully would, and counts the requests.
        roles = new RoleProvider(cluster, new LeaderElector() {
            @Override
            public void requestElection() {
                elections.incrementAndGet();
                cluster.assignLeader(cluster.nodes().stream().filter(ClusterNode::isUp)
                        .map(ClusterNode::id).max(Integer::compare).orElse(null));
            }

            @Override
            public long electionBoundMillis() {
                return 100;
            }
        });
        module = new ClockSyncModule(cluster, PROPERTIES, bus, wallClock, roles, Executors.newVirtualThreadPerTaskExecutor());
    }

    @AfterEach
    void tearDown() {
        for (int i = 1; i <= cluster.size(); i++) {
            if (!cluster.node(i).isUp()) {
                cluster.node(i).recover();
            }
        }
        module.close();
        roles.close();
        cluster.close();
        bus.close();
    }

    @Test
    @DisplayName("module metadata and initial status")
    void metadataAndStatus() {
        assertThat(module.id()).isEqualTo("clocksync");
        assertThat(module.labNumber()).isEqualTo(3);
        assertThat(module.title()).isEqualTo("Clock Synchronization");
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
    }

    @Test
    @DisplayName("overview reports live nodes, the leader as time daemon (null before any election, never electing one), and simulated drift")
    void overviewState() {
        ClockSyncOverviewDto overview = module.overview();
        assertThat(overview.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(overview.timeDaemonNodeId()).isNull();
        assertThat(overview.abortedRound()).isNull();
        assertThat(overview.nodes()).hasSize(3);
        assertThat(overview.limits().maxTrafficSeconds()).isEqualTo(30);
        assertThat(overview.limits().retainedEventsCapacity()).isEqualTo(2000);
        assertThat(overview.notes()).anyMatch(note -> note.startsWith("The time daemon is the leader elected by Experiment 4."));
        assertThat(elections).hasValue(0);

        module.runBerkeleyRoundDirect(400L);
        assertThat(module.overview().timeDaemonNodeId()).isEqualTo(3);

        // When the leader crashes there is no daemon until the next election; reading asks for none
        cluster.crash(3);
        assertThat(module.overview().timeDaemonNodeId()).isNull();
        assertThat(elections).hasValue(1);
    }

    @Test
    @DisplayName("recordLocalEvent advances Lamport clock and appends to event log")
    void localEventAdvancesClock() {
        LocalEventResult res1 = module.recordLocalEvent(1, new LocalEventCommand("Step 1"));
        assertThat(res1.nodeId()).isEqualTo(1);
        assertThat(res1.lamportTime()).isGreaterThanOrEqualTo(1L);
        assertThat(res1.description()).isEqualTo("Step 1");

        LocalEventResult res2 = module.recordLocalEvent(1, null);
        assertThat(res2.lamportTime()).isEqualTo(res1.lamportTime() + 1);

        assertThat(module.eventLog().countForNode(1, com.udcf.modules.clocksync.lamport.ClockEventType.LOCAL))
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("recordLocalEvent on a crashed node throws NodeDownException")
    void localEventOnCrashedNodeThrows() {
        cluster.node(2).crash();
        assertThatThrownBy(() -> module.recordLocalEvent(2, new LocalEventCommand("fail")))
                .isInstanceOf(NodeDownException.class);
    }

    @Test
    @DisplayName("sendLamportMessage delivers over UDP, updates Lamport clocks, and links messageId")
    void sendMessageDelivers() {
        SendLamportResult result = module.sendLamportMessage(new SendMessageCommand(1, 2, "Hello Node 2"));
        assertThat(result.from()).isEqualTo(1);
        assertThat(result.to()).isEqualTo(2);
        assertThat(result.deliveryStatus()).isEqualTo("SENT");
        assertThat(result.messageId()).isPositive();

        await().untilAsserted(() -> {
            assertThat(module.eventLog().countForNode(2, com.udcf.modules.clocksync.lamport.ClockEventType.RECV))
                    .isGreaterThanOrEqualTo(1);
            assertThat(cluster.node(2).clock().current())
                    .isGreaterThan(result.lamportTime());
        });
    }

    @Test
    @DisplayName("UDP honesty: sending to a crashed node returns deliveryStatus UNKNOWN without error")
    void udpHonestyToCrashedNode() {
        cluster.node(3).crash();

        long clockBefore = cluster.node(1).clock().current();
        SendLamportResult result = module.sendLamportMessage(new SendMessageCommand(1, 3, "Message to dead node"));

        assertThat(result.deliveryStatus()).isEqualTo("UNKNOWN");
        assertThat(result.deliveryNote()).contains("crashed node 3");
        assertThat(result.lamportTime()).isGreaterThan(clockBefore);
    }

    @Test
    @DisplayName("sending from a crashed node throws NodeDownException")
    void sendFromCrashedNodeThrows() {
        cluster.node(1).crash();
        assertThatThrownBy(() -> module.sendLamportMessage(new SendMessageCommand(1, 2, "Test")))
                .isInstanceOf(NodeDownException.class);
    }

    @Test
    @DisplayName("updateDrift modifies offset and rate, which survive crash and recover")
    void driftUpdateSurvivesCrashRecover() {
        NodeDriftResponseDto updated = module.updateDrift(2, new NodeDriftUpdateCommand(120L, 2.5));
        assertThat(updated.offsetMillis()).isEqualTo(120L);
        assertThat(updated.driftRateMsPerSec()).isEqualTo(2.5);
        assertThat(updated.simulated()).isTrue();

        cluster.node(2).crash();
        cluster.node(2).recover();

        NodeDriftResponseDto snapshot = module.overview().nodes().stream()
                .filter(n -> n.nodeId() == 2)
                .findFirst()
                .orElseThrow()
                .simulatedDrift();

        assertThat(snapshot.driftRateMsPerSec()).isEqualTo(2.5);
    }

    @Test
    @DisplayName("runBerkeleyRoundDirect coordinates round and produces BerkeleyRoundDto")
    void berkeleyRoundExecution() {
        BerkeleyRoundDto round = module.runBerkeleyRoundDirect(400L);
        assertThat(round.daemonNodeId()).isEqualTo(3);
        assertThat(round.participatingNodes()).contains(1, 2, 3);
        assertThat(round.adjustments()).hasSize(3);
        assertThat(round.simulated()).isTrue();
        assertThat(round.spreadAfterMillis()).isLessThanOrEqualTo(round.spreadBeforeMillis());
    }

    @Test
    @DisplayName("ModuleActionGuard throws ModuleBusyException on concurrent actions")
    void guardRejectsConcurrentActions() {
        try (ModuleActionGuard.ActionTicket ticket = module.guard().begin("Manual task")) {
            assertThat(module.status()).isEqualTo(ModuleStatus.BUSY);
            assertThatThrownBy(() -> module.runBerkeleyRoundDirect(300L))
                    .isInstanceOf(ModuleBusyException.class);
        }
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
    }

    @Test
    @DisplayName("causal verification passes on valid traffic and reports 0 violations after reset")
    void causalVerificationAndReset() {
        module.recordLocalEvent(1, new LocalEventCommand("e1"));
        module.sendLamportMessage(new SendMessageCommand(1, 2, "m1"));
        await().untilAsserted(() -> assertThat(module.eventLog().size()).isGreaterThanOrEqualTo(2));

        CausalVerificationDto verification = module.verifyCausalInvariants();
        assertThat(verification.passed()).isTrue();
        assertThat(verification.violationsCount()).isZero();

        // Cluster reset clears event log and restores initial drift
        module.reset();
        assertThat(module.eventLog().size()).isZero();

        CausalVerificationDto afterReset = module.verifyCausalInvariants();
        assertThat(afterReset.passed()).isTrue();
        assertThat(afterReset.violationsCount()).isZero();
        assertThat(afterReset.totalEventsChecked()).isZero();
    }

    @Test
    @DisplayName("timeline query returns events up to limit and validates parameters")
    void timelineQuery() {
        module.recordLocalEvent(1, new LocalEventCommand("ev1"));
        module.recordLocalEvent(2, new LocalEventCommand("ev2"));

        TimelineResponseDto timeline = module.timeline(10);
        assertThat(timeline.returnedCount()).isGreaterThanOrEqualTo(2);
        assertThat(timeline.events()).allSatisfy(e -> assertThat(e.nodeId()).isIn(1, 2, 3));

        assertThatThrownBy(() -> module.timeline(0))
                .isInstanceOf(InvalidParameterException.class);
        assertThatThrownBy(() -> module.timeline(5000))
                .isInstanceOf(InvalidParameterException.class);
    }

    // ------------------------------------------------------------------ time daemon = elected leader (9A.4)

    private List<ClusterEvent> events(String eventModule, String type) {
        return bus.query(eventModule, null, 2000).stream().filter(e -> e.type().equals(type)).toList();
    }

    /** Waits until the roles dispatcher has delivered every leader change announced so far. */
    private void awaitDispatcher() throws InterruptedException {
        CountDownLatch delivered = new CountDownLatch(1);
        roles.afterPendingChanges(delivered::countDown);
        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("the daemon is the current leader, not the lowest node, and no election is asked for while one leads")
    void daemonIsTheLeader() {
        cluster.assignLeader(2);

        BerkeleyRoundDto round = module.runBerkeleyRoundDirect(400L);

        assertThat(round.daemonNodeId()).isEqualTo(2);
        assertThat(events(ClockSyncModule.ID, "BERKELEY_ROUND_STARTED")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("daemonId", 2));
        assertThat(elections).hasValue(0);
    }

    @Test
    @DisplayName("no leader: NoLeaderException (409 NO_LEADER), no round starts and nothing changes")
    void noLeaderRefusesRound() {
        cluster.nodes().forEach(node -> cluster.crash(node.id()));

        assertThatThrownBy(() -> module.runBerkeleyRound(null)).isInstanceOf(NoLeaderException.class)
                .hasMessageStartingWith("No leader could be elected");
        assertThat(events(ClockSyncModule.ID, "BERKELEY_ROUND_STARTED")).isEmpty();
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(module.overview().latestRound()).isNull();
    }

    @Test
    @DisplayName("a round right after a re-election: exactly one TIME_DAEMON_CHANGED, after LEADER_CHANGED and before the round starts")
    void daemonChangeOnceBeforeNextRound() throws Exception {
        module.runBerkeleyRoundDirect(400L);   // daemon 3
        cluster.assignLeader(2);
        module.runBerkeleyRoundDirect(400L);   // races the listener; one of them publishes
        awaitDispatcher();

        List<ClusterEvent> changes = events(ClockSyncModule.ID, "TIME_DAEMON_CHANGED");
        assertThat(changes).singleElement().satisfies(e -> {
            assertThat(e.nodeId()).isEqualTo(2);
            assertThat(e.peerId()).isEqualTo(3);
            assertThat(e.data()).containsEntry("nodeId", 2).containsEntry("previousNodeId", 3)
                    .containsEntry("cause", "ELECTED");
            assertThat(e.message()).isEqualTo("Node 2 is the time daemon for the next Berkeley round: "
                    + "Experiment 4 elected node 2 in place of node 3.");
        });
        ClusterEvent elected = events("cluster", "LEADER_CHANGED").stream()
                .filter(e -> Integer.valueOf(2).equals(e.data().get("leaderId"))).findFirst().orElseThrow();
        ClusterEvent secondRound = events(ClockSyncModule.ID, "BERKELEY_ROUND_STARTED").get(1);
        assertThat(changes.get(0).sequence()).isGreaterThan(elected.sequence()).isLessThan(secondRound.sequence());
        assertThat(secondRound.data()).containsEntry("daemonId", 2);
    }

    @Test
    @DisplayName("daemon crashed: nothing for the vacancy, then cause LEADER_CRASHED when the next leader is elected")
    void crashedDaemonCause() throws Exception {
        module.runBerkeleyRoundDirect(400L);   // daemon 3
        cluster.crash(3);
        awaitDispatcher();
        assertThat(events(ClockSyncModule.ID, "TIME_DAEMON_CHANGED")).isEmpty();

        cluster.assignLeader(2);
        await().atMost(Duration.ofSeconds(5)).until(() -> !events(ClockSyncModule.ID, "TIME_DAEMON_CHANGED").isEmpty());

        assertThat(events(ClockSyncModule.ID, "TIME_DAEMON_CHANGED")).singleElement().satisfies(e -> {
            assertThat(e.data()).containsEntry("cause", "LEADER_CRASHED");
            assertThat(e.message()).isEqualTo("Node 2 is the time daemon for the next Berkeley round: "
                    + "node 3 crashed and Experiment 4 elected node 2.");
        });
    }

    @Test
    @DisplayName("quiet before the first round, after a reset and after close")
    void quietWhenNotUsed() throws Exception {
        cluster.assignLeader(2);   // no round yet
        awaitDispatcher();
        module.runBerkeleyRoundDirect(400L);   // daemon 2: the first daemon is not a change
        module.reset();
        cluster.assignLeader(1);
        awaitDispatcher();
        module.runBerkeleyRoundDirect(400L);   // daemon 1 after the reset
        module.close();
        cluster.assignLeader(3);
        awaitDispatcher();

        assertThat(events(ClockSyncModule.ID, "TIME_DAEMON_CHANGED")).isEmpty();
    }

    @Test
    @DisplayName("daemon crashes during an accepted round: the overview shows the abort, latestRound keeps the last real round")
    void abortedRoundShownInOverview() {
        BerkeleyRoundDto real = module.runBerkeleyRoundDirect(400L);   // daemon 3
        // Node 2 stays up but its clock service stops, so it never replies: the poll waits the 500 ms poll timeout
        ClockNodeService.find(cluster.node(2)).orElseThrow().stop();

        BerkeleyRoundAcceptedDto accepted = module.runBerkeleyRound(new BerkeleyRoundCommand(400L));
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(5))
                .until(() -> events(ClockSyncModule.ID, "BERKELEY_ROUND_STARTED").size() == 2);
        cluster.crash(3);
        await().atMost(Duration.ofSeconds(5)).until(() -> module.status() == ModuleStatus.IDLE
                && module.overview().abortedRound() != null);

        ClockSyncOverviewDto overview = module.overview();
        assertThat(overview.abortedRound().roundId()).isEqualTo(accepted.roundId());
        assertThat(overview.abortedRound().daemonNodeId()).isEqualTo(3);
        assertThat(overview.abortedRound().reason()).startsWith("The time daemon, node 3, crashed during the ");
        assertThat(overview.latestRound()).isEqualTo(real);
        assertThat(events(ClockSyncModule.ID, "BERKELEY_ROUND_ABORTED")).hasSize(1);
        assertThat(events(ClockSyncModule.ID, "BERKELEY_ROUND_FINISHED")).hasSize(1);
    }
}
