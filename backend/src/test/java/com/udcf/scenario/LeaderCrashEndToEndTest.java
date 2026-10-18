package com.udcf.scenario;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterResetService;
import com.udcf.core.cluster.RoleProvider;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.clocksync.ClockSyncModule;
import com.udcf.modules.clocksync.dto.BerkeleyRoundCommand;
import com.udcf.modules.faulttolerance.FaultToleranceModule;
import com.udcf.modules.faulttolerance.dto.StreamCommand;
import com.udcf.modules.mapreduce.InputType;
import com.udcf.modules.mapreduce.MapReduceModule;
import com.udcf.modules.mapreduce.dto.RunCommand;
import com.udcf.modules.mapreduce.dto.RunState;
import com.udcf.modules.mpi.CollectiveOperation;
import com.udcf.modules.mpi.MpiModule;
import com.udcf.modules.mpi.dto.CollectiveCommand;
import com.udcf.modules.replication.ConsistencyModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 9A's leader-crash test on the real five-node cluster (real sockets on 127.0.0.1): with Experiments 3,
 * 7, 8 and 9 used since the reset, crashing the leader node gives one {@code LEADER_CHANGED} to the new leader,
 * followed by bus sequence by {@code TIME_DAEMON_CHANGED}, {@code COORDINATOR_CHANGED}, {@code MPI_ROOT_CHANGED},
 * {@code PRIMARY_PROMOTED} and {@code REPLICATION_ROLES_CHANGED}, all for that same new leader (link L1).
 *
 * <p>Ports (Track B block, below 32768): RMI 26861-26865, clock 26871-26875, election 26881-26885,
 * replication 26891-26895, MapReduce 26161-26165; requests 26170 is a base only (never started). The context
 * is closed after the class. Waits are Awaitility polls with a 30 s deadline (Experiment 8's worst-case
 * failover is about 10.3 s), never fixed sleeps. The change events carry the new holder's Lamport clock and
 * {@code LEADER_CHANGED} the cluster clock, so "after" is checked by bus sequence.</p>
 */
@SpringBootTest(properties = {
        "udcf.cluster.ports.rmi-base=26860", "udcf.cluster.ports.clock-base=26870",
        "udcf.cluster.ports.election-base=26880", "udcf.cluster.ports.replication-base=26890",
        "udcf.cluster.ports.requests-base=26170", "udcf.cluster.ports.mapreduce-base=26160"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LeaderCrashEndToEndTest {

    private static final Duration DEADLINE = Duration.ofSeconds(30);

    @Autowired
    private Cluster cluster;

    @Autowired
    private RoleProvider roles;

    @Autowired
    private ClusterEventBus bus;

    @Autowired
    private ClusterResetService resetService;

    @Autowired
    private ClockSyncModule clockSync;

    @Autowired
    private MapReduceModule mapReduce;

    @Autowired
    private MpiModule mpi;

    @Autowired
    private FaultToleranceModule faultTolerance;

    private List<ClusterEvent> events(long after, String module, String type) {
        return bus.query(module, null, Integer.MAX_VALUE).stream()
                .filter(e -> e.sequence() > after && e.type().equals(type))
                .sorted(Comparator.comparingLong(ClusterEvent::sequence)).toList();
    }

    private ClusterEvent awaitEvent(long after, String module, String type, Predicate<ClusterEvent> match) {
        return await().atMost(DEADLINE).until(() -> events(after, module, type).stream().filter(match).findFirst(),
                Optional::isPresent).orElseThrow();
    }

    private static boolean is(ClusterEvent event, String key, int value) {
        return EventData.is(event, key, value);
    }

    @Test
    @DisplayName("crash the leader: LEADER_CHANGED, then the new time daemon, MapReduce coordinator, MPI root and replication primary are all the new leader")
    void everyRoleFollowsTheNewLeader() {
        await().atMost(DEADLINE).pollInterval(Duration.ofMillis(200)).ignoreException(ModuleBusyException.class)
                .until(() -> {
                    resetService.reset();
                    return true;
                });

        // Experiment 3: the first Berkeley round asks Experiment 4 for a leader; the daemon is that leader.
        long daemon = clockSync.runBerkeleyRound(new BerkeleyRoundCommand(null)).daemonNodeId();
        await().atMost(DEADLINE).until(() -> clockSync.status() == ModuleStatus.IDLE);
        int leader = roles.leaderId().orElseThrow();
        assertThat(leader).isEqualTo(cluster.size());   // Bully: the highest live node
        assertThat(daemon).isEqualTo(leader);

        // Experiment 7: one job coordinated by the leader.
        String jobId = mapReduce.startRun(new RunCommand("word-count", InputType.SAMPLE, null, null)).runId();
        await().atMost(DEADLINE).until(() -> mapReduce.run(jobId).state() != RunState.RUNNING
                && mapReduce.status() != ModuleStatus.BUSY);
        assertThat(mapReduce.run(jobId).state()).isEqualTo(RunState.COMPLETED);
        assertThat(mapReduce.run(jobId).coordinatorId()).isEqualTo(leader);

        // Experiment 9: one collective with the default root, the leader.
        assertThat(mpi.run(new CollectiveCommand(CollectiveOperation.BROADCAST, 10, null, null)).rootNodeId())
                .isEqualTo(leader);

        // Experiment 8: the leader is the replication primary of a running synchronous stream.
        long started = bus.publishedCount();
        faultTolerance.startStream(new StreamCommand(ConsistencyModel.SYNCHRONOUS, 4));
        awaitEvent(started, FaultToleranceModule.ID, "PRIMARY_PROMOTED", e -> e.nodeId() == leader);
        await().atMost(DEADLINE).until(() -> faultTolerance.currentState().stream().acknowledged() >= 2);

        // Crash the leader node on every protocol, through the cluster.
        long mark = bus.publishedCount();
        assertThat(cluster.crash(leader)).isTrue();

        ClusterEvent elected = awaitEvent(mark, "cluster", "LEADER_CHANGED",
                e -> EventData.integer(e, "leaderId") != null);
        int newLeader = EventData.integer(elected, "leaderId");
        assertThat(newLeader).isEqualTo(leader - 1);   // the highest live node now
        assertThat(events(mark, "cluster", "LEADER_CHANGED")).as("one election of a new leader, no flapping")
                .filteredOn(e -> EventData.integer(e, "leaderId") != null).hasSize(1);

        for (String[] change : new String[][]{{ClockSyncModule.ID, "TIME_DAEMON_CHANGED"},
                {MapReduceModule.ID, "COORDINATOR_CHANGED"}, {MpiModule.ID, "MPI_ROOT_CHANGED"}}) {
            ClusterEvent event = awaitEvent(mark, change[0], change[1], e -> is(e, "nodeId", newLeader));
            assertThat(event.sequence()).as("%s after LEADER_CHANGED by bus sequence", change[1])
                    .isGreaterThan(elected.sequence());
            assertThat(event.nodeId()).isEqualTo(newLeader);
            assertThat(EventData.integer(event, "previousNodeId")).as(change[1]).isEqualTo(leader);
            assertThat(event.data().get("cause")).as(change[1]).isEqualTo("LEADER_CRASHED");
            assertThat(events(mark, change[0], change[1])).as("%s once", change[1]).hasSize(1);
        }

        ClusterEvent promoted = awaitEvent(mark, FaultToleranceModule.ID, "PRIMARY_PROMOTED",
                e -> e.nodeId() == newLeader);
        assertThat(promoted.sequence()).isGreaterThan(elected.sequence());
        ClusterEvent primaryRoles = awaitEvent(mark, "cluster", "REPLICATION_ROLES_CHANGED",
                e -> is(e, "primaryId", newLeader));
        assertThat(primaryRoles.sequence()).isGreaterThan(elected.sequence());

        await().atMost(DEADLINE).until(() -> roles.primaryId().equals(Optional.of(newLeader)));
        assertThat(roles.leaderId()).contains(newLeader);
        // The next round, job and collective use the new leader at once.
        assertThat(clockSync.runBerkeleyRound(new BerkeleyRoundCommand(null)).daemonNodeId()).isEqualTo(newLeader);
        await().atMost(DEADLINE).until(() -> clockSync.status() == ModuleStatus.IDLE);
        assertThat(mpi.run(new CollectiveCommand(CollectiveOperation.BROADCAST, 10, null, null)).rootNodeId())
                .isEqualTo(newLeader);

        faultTolerance.stopStream();
    }
}
