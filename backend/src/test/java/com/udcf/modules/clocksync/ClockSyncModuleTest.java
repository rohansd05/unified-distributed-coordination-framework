package com.udcf.modules.clocksync;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.core.module.ModuleStatus;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for {@link ClockSyncModule} using standalone cluster and real UDP sockets
 * on reserved port range 48100-48899.
 */
class ClockSyncModuleTest {

    private static final ClusterProperties CLUSTER_PROPS = new ClusterProperties(
            3,
            List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(48100, 48400, 48300, 48500, 48700, 48800)
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

    @BeforeEach
    void setUp() {
        wallClock = Clock.systemUTC();
        bus = new ClusterEventBus(new EventProperties(2000, 2000), wallClock);
        cluster = new Cluster(CLUSTER_PROPS, bus);
        module = new ClockSyncModule(cluster, PROPERTIES, bus, wallClock, Executors.newVirtualThreadPerTaskExecutor());
    }

    @AfterEach
    void tearDown() {
        for (int i = 1; i <= cluster.size(); i++) {
            if (!cluster.node(i).isUp()) {
                cluster.node(i).recover();
            }
        }
        module.close();
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
    @DisplayName("overview reports live nodes, time daemon selection, and simulated drift")
    void overviewState() {
        ClockSyncOverviewDto overview = module.overview();
        assertThat(overview.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(overview.timeDaemonNodeId()).isEqualTo(1);
        assertThat(overview.nodes()).hasSize(3);
        assertThat(overview.limits().maxTrafficSeconds()).isEqualTo(30);
        assertThat(overview.limits().retainedEventsCapacity()).isEqualTo(2000);
        assertThat(overview.notes()).isNotEmpty();

        // When daemon node 1 crashes, node 2 becomes daemon
        cluster.node(1).crash();
        assertThat(module.overview().timeDaemonNodeId()).isEqualTo(2);

        cluster.node(1).recover();
        assertThat(module.overview().timeDaemonNodeId()).isEqualTo(1);
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
        assertThat(round.daemonNodeId()).isEqualTo(1);
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
}
