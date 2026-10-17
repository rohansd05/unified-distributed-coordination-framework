package com.udcf.modules.mapreduce;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.cluster.RoleProvider;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventLogExporter;
import com.udcf.core.events.EventProperties;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.election.ElectionModule;
import com.udcf.modules.election.ElectionProperties;
import com.udcf.modules.mapreduce.dto.RunCommand;
import com.udcf.modules.mapreduce.dto.RunDto;
import com.udcf.modules.mapreduce.dto.RunState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Link L1 for Experiment 7 on real 127.0.0.1 sockets: the MapReduce coordinator is the leader
 * Experiment 4 elects (Bully, the highest live node), a read never starts an election, and after
 * the leader crashes the next job is coordinated by the re-elected leader, announced once with
 * COORDINATOR_CHANGED after LEADER_CHANGED and before JOB_STARTED.
 *
 * <p>Test ports (Track D block): mapreduce TCP 24551-24555, election UDP 24891-24895; the other
 * bases are placeholders nothing here binds. Election timings are short, as in Experiment 8's fixture.</p>
 */
class MapReduceLeaderIntegrationTest {

    private static final ClusterProperties.Ports PORTS = new ClusterProperties.Ports(24810, 24820, 24890, 24830, 24840, 24550);
    private static final ElectionProperties ELECTION = new ElectionProperties(400, 1500, 150, 3000, 100, 600, 10000);
    private static final RunCommand WORD_COUNT = new RunCommand("word-count", InputType.SAMPLE, null, null);

    private ClusterEventBus bus;
    private Cluster cluster;
    private ElectionModule election;
    private RoleProvider roles;
    private MapReduceModule module;

    @BeforeEach
    void setUp() {
        EventProperties events = new EventProperties(5000, 4096);
        bus = new ClusterEventBus(events, Clock.systemUTC());
        cluster = new Cluster(new ClusterProperties(5, List.of(NodeCapacity.FAST, NodeCapacity.MEDIUM, NodeCapacity.SLOW),
                PORTS), bus);
        election = new ElectionModule(cluster, ELECTION, bus, new SimpleMeterRegistry());
        roles = new RoleProvider(cluster, election);
        module = new MapReduceModule(cluster, bus, MapReduceModuleHarness.wire(4194304),
                new MapReduceModuleProperties(262144, 100000, 20, 8192), new EventLogExporter(bus), events,
                new SimpleMeterRegistry(), Clock.systemUTC(), roles);
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

    private RunDto runAndWait() {
        String runId = module.startRun(WORD_COUNT).runId();
        await().atMost(MapReduceModuleHarness.RUN_TIMEOUT).until(() -> module.run(runId).state() != RunState.RUNNING);
        await().atMost(MapReduceModuleHarness.RUN_TIMEOUT).until(() -> module.status() != ModuleStatus.BUSY);
        return module.run(runId);
    }

    private ClusterEvent jobStarted(RunDto run) {
        return events(MapReduceModule.ID, "JOB_STARTED").stream()
                .filter(e -> run.runId().equals(e.data().get("runId"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("reads never elect; the first job asks Experiment 4, and its coordinator is the elected leader (node 5)")
    void readsNeverElectAndJobUsesElectedLeader() {
        for (int i = 0; i < 3; i++) {
            assertThat(module.overview().coordinatorId()).isNull();
        }
        assertThat(events("cluster", "LEADER_CHANGED")).isEmpty();
        assertThat(bus.query("election", null, 5000)).isEmpty();

        RunDto run = runAndWait();

        assertThat(run.state()).isEqualTo(RunState.COMPLETED);
        assertThat(run.coordinatorId()).isEqualTo(5);
        assertThat(roles.leaderId()).contains(5);
        assertThat(module.overview().coordinatorId()).isEqualTo(5);
        ClusterEvent started = jobStarted(run);
        assertThat(started.nodeId()).isEqualTo(5);
        assertThat(started.sequence()).isGreaterThan(leaderChangedTo(5).sequence());
    }

    @Test
    @DisplayName("leader crash: re-election, one COORDINATOR_CHANGED (4, 5, LEADER_CRASHED) after LEADER_CHANGED and before the next JOB_STARTED, coordinated by node 4")
    void leaderCrashNextJobUsesNewLeader() {
        runAndWait();
        cluster.crash(5);
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(1))
                .until(() -> roles.leaderId().equals(Optional.of(4)));

        RunDto next = runAndWait();   // started the moment node 4 leads: it races the leader listener

        assertThat(next.state()).isEqualTo(RunState.COMPLETED);
        assertThat(next.coordinatorId()).isEqualTo(4);
        assertThat(next.workerIds()).containsExactly(1, 2, 3, 4);
        await().atMost(Duration.ofSeconds(5)).until(() -> !events(MapReduceModule.ID, "COORDINATOR_CHANGED").isEmpty());
        List<ClusterEvent> changes = events(MapReduceModule.ID, "COORDINATOR_CHANGED");
        assertThat(changes).singleElement().satisfies(e -> {
            assertThat(e.nodeId()).isEqualTo(4);
            assertThat(e.data()).containsEntry("nodeId", 4).containsEntry("previousNodeId", 5)
                    .containsEntry("cause", "LEADER_CRASHED");
            assertThat(e.message()).isEqualTo("Node 4 coordinates the next job: node 5 crashed and Experiment 4 "
                    + "elected node 4. A running job keeps its coordinator.");
        });
        assertThat(changes.get(0).sequence()).isGreaterThan(leaderChangedTo(4).sequence())
                .isLessThan(jobStarted(next).sequence());
    }
}
