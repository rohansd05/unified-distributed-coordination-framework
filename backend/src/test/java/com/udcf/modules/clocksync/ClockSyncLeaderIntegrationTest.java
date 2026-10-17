package com.udcf.modules.clocksync;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.cluster.RoleProvider;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.clocksync.dto.BerkeleyRoundAcceptedDto;
import com.udcf.modules.clocksync.dto.BerkeleyRoundCommand;
import com.udcf.modules.election.ElectionModule;
import com.udcf.modules.election.ElectionProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Link L1 for Experiment 3 on real 127.0.0.1 sockets: the Berkeley time daemon is the leader
 * Experiment 4 elects (Bully, the highest live node), a read never starts an election, and after
 * the leader crashes the next round uses the re-elected leader, announced once with
 * TIME_DAEMON_CHANGED after LEADER_CHANGED and before the round starts.
 *
 * <p>Test ports (Track D block): clock UDP 24621-24625, election UDP 24881-24885; the other bases
 * are placeholders nothing here binds. Election timings are short, as in Experiment 8's fixture.</p>
 */
class ClockSyncLeaderIntegrationTest {

    private static final ClusterProperties.Ports PORTS = new ClusterProperties.Ports(24810, 24620, 24880, 24820, 24830, 24840);
    private static final ElectionProperties ELECTION = new ElectionProperties(400, 1500, 150, 3000, 100, 600, 10000);
    private static final ClockSyncProperties PROPERTIES = new ClockSyncProperties(500L, 300L, 5, 30, 4, 20, 2000,
            Map.of(1, new ClockSyncProperties.NodeDriftConfig(0L, 0.0),
                    2, new ClockSyncProperties.NodeDriftConfig(40L, 1.5),
                    3, new ClockSyncProperties.NodeDriftConfig(-30L, -0.5),
                    4, new ClockSyncProperties.NodeDriftConfig(80L, 0.0),
                    5, new ClockSyncProperties.NodeDriftConfig(-60L, 0.5)));
    private static final Duration ELECTED_WITHIN = Duration.ofSeconds(15);

    private ClusterEventBus bus;
    private Cluster cluster;
    private ElectionModule election;
    private RoleProvider roles;
    private ClockSyncModule module;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), Clock.systemUTC());
        cluster = new Cluster(new ClusterProperties(5, List.of(NodeCapacity.FAST, NodeCapacity.MEDIUM, NodeCapacity.SLOW),
                PORTS), bus);
        election = new ElectionModule(cluster, ELECTION, bus, new SimpleMeterRegistry());
        roles = new RoleProvider(cluster, election);
        module = new ClockSyncModule(cluster, PROPERTIES, bus, Clock.systemUTC(), roles);
    }

    @AfterEach
    void tearDown() {
        module.close();
        roles.close();
        election.close();
        cluster.close();
        bus.close();
    }

    private List<ClusterEvent> events(String eventModule, String type) {
        return bus.query(eventModule, null, 5000).stream().filter(e -> e.type().equals(type)).toList();
    }

    private ClusterEvent leaderChangedTo(int leaderId) {
        return events("cluster", "LEADER_CHANGED").stream()
                .filter(e -> Integer.valueOf(leaderId).equals(e.data().get("leaderId"))).reduce((a, b) -> b).orElseThrow();
    }

    private BerkeleyRoundAcceptedDto roundAndWait() {
        BerkeleyRoundAcceptedDto accepted = module.runBerkeleyRound(new BerkeleyRoundCommand(300L));
        await().atMost(Duration.ofSeconds(10)).until(() -> module.status() == ModuleStatus.IDLE);
        return accepted;
    }

    private void awaitDispatcher() throws InterruptedException {
        CountDownLatch delivered = new CountDownLatch(1);
        roles.afterPendingChanges(delivered::countDown);
        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("reads never elect; the first round asks Experiment 4, and its daemon is the elected leader (node 5)")
    void readsNeverElectAndRoundUsesElectedLeader() {
        for (int i = 0; i < 3; i++) {
            assertThat(module.overview().timeDaemonNodeId()).isNull();
        }
        assertThat(events("cluster", "LEADER_CHANGED")).isEmpty();
        assertThat(bus.query("election", null, 5000)).isEmpty();

        BerkeleyRoundAcceptedDto accepted = roundAndWait();

        assertThat(accepted.daemonNodeId()).isEqualTo(5);
        assertThat(roles.leaderId()).contains(5);
        assertThat(module.overview().timeDaemonNodeId()).isEqualTo(5);
        assertThat(module.overview().latestRound().daemonNodeId()).isEqualTo(5);
        assertThat(events(ClockSyncModule.ID, "BERKELEY_ROUND_STARTED")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("daemonId", 5));
        assertThat(leaderChangedTo(5).sequence())
                .isLessThan(events(ClockSyncModule.ID, "BERKELEY_ROUND_STARTED").get(0).sequence());
    }

    @Test
    @DisplayName("leader crash: re-election, TIME_DAEMON_CHANGED (4, 5, LEADER_CRASHED) after LEADER_CHANGED, next round on node 4")
    void leaderCrashNextRoundUsesNewLeader() {
        roundAndWait();
        cluster.crash(5);

        await().atMost(ELECTED_WITHIN).until(() -> roles.leaderId().equals(Optional.of(4)));
        await().atMost(Duration.ofSeconds(5)).until(() -> !events(ClockSyncModule.ID, "TIME_DAEMON_CHANGED").isEmpty());
        ClusterEvent change = events(ClockSyncModule.ID, "TIME_DAEMON_CHANGED").get(0);
        assertThat(change.nodeId()).isEqualTo(4);
        assertThat(change.data()).containsEntry("nodeId", 4).containsEntry("previousNodeId", 5)
                .containsEntry("cause", "LEADER_CRASHED");
        assertThat(change.message()).isEqualTo("Node 4 is the time daemon for the next Berkeley round: "
                + "node 5 crashed and Experiment 4 elected node 4.");
        assertThat(change.sequence()).isGreaterThan(leaderChangedTo(4).sequence());

        BerkeleyRoundAcceptedDto next = roundAndWait();

        assertThat(next.daemonNodeId()).isEqualTo(4);
        List<ClusterEvent> started = events(ClockSyncModule.ID, "BERKELEY_ROUND_STARTED");
        assertThat(started).hasSize(2);
        assertThat(started.get(1).data()).containsEntry("daemonId", 4);
        assertThat(started.get(1).sequence()).isGreaterThan(change.sequence());
        assertThat(events(ClockSyncModule.ID, "TIME_DAEMON_CHANGED")).hasSize(1);
    }

    @Test
    @DisplayName("a round started the moment the new leader is elected: exactly one TIME_DAEMON_CHANGED, before that round starts")
    void roundRightAfterReElectionAnnouncesOnce() throws Exception {
        roundAndWait();
        cluster.crash(5);
        await().atMost(ELECTED_WITHIN).pollInterval(Duration.ofMillis(1)).until(() -> roles.leaderId().equals(Optional.of(4)));

        roundAndWait();   // races the leader listener for the one compare-and-set
        awaitDispatcher();

        List<ClusterEvent> changes = events(ClockSyncModule.ID, "TIME_DAEMON_CHANGED");
        assertThat(changes).hasSize(1);
        ClusterEvent secondStart = events(ClockSyncModule.ID, "BERKELEY_ROUND_STARTED").get(1);
        assertThat(secondStart.data()).containsEntry("daemonId", 4);
        assertThat(changes.get(0).sequence()).isGreaterThan(leaderChangedTo(4).sequence())
                .isLessThan(secondStart.sequence());
    }
}
