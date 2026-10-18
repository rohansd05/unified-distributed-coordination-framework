package com.udcf.modules.replication;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterResetService;
import com.udcf.core.cluster.RoleProvider;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.modules.faulttolerance.FaultToleranceModule;
import com.udcf.modules.faulttolerance.dto.StreamCommand;
import com.udcf.modules.faulttolerance.dto.StreamDto;
import com.udcf.modules.matrix.MatrixModule;
import com.udcf.modules.matrix.dto.MatrixRunCommand;
import com.udcf.modules.matrix.dto.MatrixRunDto;
import com.udcf.modules.mpi.CollectiveOperation;
import com.udcf.modules.mpi.MpiModule;
import com.udcf.modules.mpi.dto.CollectiveCommand;
import com.udcf.modules.mpi.dto.CollectiveDto;
import com.udcf.modules.mpi.dto.RootSource;
import com.udcf.modules.replication.dto.WriteCommand;
import com.udcf.modules.replication.dto.WriteDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Link L1 for Experiments 5, 9 and 10 on the real five-node cluster (9A.3): every module follows the leader
 * elected by Experiment 4, Experiment 5 never moves the primary away from it while Experiment 8 streams, and
 * after the leader crashes the next write, collective and matrix run use the new leader.
 *
 * <p>Real sockets on Jai's test ports: RMI 28181 to 28185, clock 28191 to 28195, election 28221 to 28225,
 * replication 28231 to 28235, requests 28261 to 28265 (MapReduce 28271 to 28275 never binds). The context is
 * closed after the class. Waits are Awaitility polls with a 30 s deadline (Experiment 8's worst-case failover is
 * about 10.3 s), never fixed sleeps. Event order is checked by bus sequence.</p>
 */
@SpringBootTest(properties = {"udcf.cluster.ports.rmi-base=28180", "udcf.cluster.ports.clock-base=28190",
        "udcf.cluster.ports.election-base=28220", "udcf.cluster.ports.replication-base=28230",
        "udcf.cluster.ports.requests-base=28260", "udcf.cluster.ports.mapreduce-base=28270"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LeaderRolesIntegrationTest {

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
    private ReplicationModule replication;

    @Autowired
    private FaultToleranceModule faultTolerance;

    @Autowired
    private MpiModule mpi;

    @Autowired
    private MatrixModule matrix;

    @BeforeEach
    void cleanSlate() {
        // A reset is refused while a module is BUSY (for example a recovery election): retry.
        await().atMost(DEADLINE).pollInterval(Duration.ofMillis(200)).ignoreException(ModuleBusyException.class)
                .until(() -> {
                    resetService.reset();
                    return true;
                });
    }

    private List<ClusterEvent> events(String module, String type) {
        return bus.query(module, null, Integer.MAX_VALUE).stream()
                .filter(e -> e.type().equals(type))
                .sorted(Comparator.comparingLong(ClusterEvent::sequence)).toList();
    }

    private List<ClusterEvent> eventsAfter(long sequence, String module, String type) {
        return events(module, type).stream().filter(e -> e.sequence() > sequence).toList();
    }

    private long lastSequence() {
        return bus.query(null, null, Integer.MAX_VALUE).stream().mapToLong(ClusterEvent::sequence).max().orElse(0);
    }

    private ClusterEvent awaitEvent(String module, String type, Predicate<ClusterEvent> match) {
        return await().atMost(DEADLINE).until(() -> events(module, type).stream().filter(match).findFirst(),
                found -> found.isPresent()).orElseThrow();
    }

    private WriteDto write(String key) {
        return replication.write(new WriteCommand(key, "v", ConsistencyModel.SYNCHRONOUS));
    }

    private StreamDto stream() {
        return faultTolerance.currentState().stream();
    }

    @Test
    @DisplayName("Experiment 5 writes while Experiment 8 streams keep the primary on the leader: no step-down, no stale epoch, nothing given up")
    void experimentFiveKeepsThePrimaryOnTheLeader() {
        WriteDto first = write("before-8");   // Experiment 5 makes the leader primary first
        int leader = first.primaryNodeId();
        assertThat(leader).isEqualTo(5);   // Bully: the highest live node

        faultTolerance.start();
        // Experiment 8 still promotes normally when Experiment 5 already made the leader primary (R5).
        awaitEvent(FaultToleranceModule.ID, "PRIMARY_PROMOTED", e -> e.nodeId() == leader);
        faultTolerance.startStream(new StreamCommand(ConsistencyModel.SYNCHRONOUS, 4));
        await().atMost(DEADLINE).until(() -> stream().acknowledged() >= 3);
        OptionalLong epoch = ReplicationNodeService.find(cluster.node(leader)).orElseThrow().primaryEpoch();
        long marker = lastSequence();

        for (int i = 0; i < 3; i++) {
            assertThat(write("during-8-" + i).primaryNodeId()).isEqualTo(leader);
        }
        int acknowledgedAfterWrites = stream().acknowledged();
        await().atMost(DEADLINE).until(() -> stream().acknowledged() >= acknowledgedAfterWrites + 3);
        faultTolerance.stopStream();
        await().atMost(DEADLINE).until(() -> "STOPPED".equals(stream().state()) && stream().inFlightSequence() == null);

        // A node answers "not primary" only once its replication service stops acting as primary: none did.
        assertThat(eventsAfter(marker, ReplicationModule.ID, "PRIMARY_STEPPED_DOWN")).isEmpty();
        assertThat(eventsAfter(marker, ReplicationModule.ID, "PRIMARY_SUPERSEDED")).isEmpty();
        assertThat(eventsAfter(marker, ReplicationModule.ID, "PRIMARY_SELECTED")).isEmpty();
        assertThat(eventsAfter(marker, FaultToleranceModule.ID, "STALE_EPOCH_REFUSED")).isEmpty();
        assertThat(stream().gaveUp()).isZero();
        assertThat(stream().acknowledged()).isEqualTo(stream().sent());
        assertThat(roles.primaryId()).contains(leader);
        assertThat(ReplicationNodeService.find(cluster.node(leader)).orElseThrow().primaryEpoch()).isEqualTo(epoch);
    }

    @Test
    @DisplayName("after the leader crashes and node 4 is elected, the next write, collective and matrix run use node 4; MPI_ROOT_CHANGED once, in order")
    void nextActionsFollowTheNewLeader() {
        assertThat(write("k").primaryNodeId()).isEqualTo(5);
        CollectiveDto before = mpi.run(new CollectiveCommand(CollectiveOperation.BROADCAST, 10, null, null));
        assertThat(before.rootNodeId()).isEqualTo(5);
        assertThat(before.rootSource()).isEqualTo(RootSource.LEADER);
        assertThat(matrix.run(new MatrixRunCommand(10, null, null)).rootNodeId()).isEqualTo(5);

        cluster.crash(5);
        await().atMost(DEADLINE).until(() -> roles.leaderId().filter(id -> id == 4).isPresent());

        WriteDto write = write("k");
        CollectiveDto collective = mpi.run(new CollectiveCommand(CollectiveOperation.BROADCAST, 10, null, null));
        MatrixRunDto run = matrix.run(new MatrixRunCommand(10, null, null));

        assertThat(write.primaryNodeId()).isEqualTo(4);
        assertThat(write.takeover().previousPrimaryNodeId()).isEqualTo(5);
        assertThat(roles.primaryId()).contains(4);
        assertThat(collective.rootNodeId()).isEqualTo(4);
        assertThat(collective.rootSource()).isEqualTo(RootSource.LEADER);
        assertThat(collective.skippedNodeIds()).containsExactly(5);
        assertThat(run.rootNodeId()).isEqualTo(4);
        assertThat(run.rootSource()).isEqualTo(RootSource.LEADER);

        List<ClusterEvent> changed = events(MpiModule.ID, "MPI_ROOT_CHANGED");
        assertThat(changed).singleElement().satisfies(e -> {
            assertThat(e.nodeId()).isEqualTo(4);
            assertThat(e.peerId()).isEqualTo(5);
            assertThat(e.data()).containsEntry("previousNodeId", 5).containsEntry("cause", "LEADER_CRASHED");
        });
        ClusterEvent elected = events("cluster", "LEADER_CHANGED").stream()
                .filter(e -> Integer.valueOf(4).equals(e.data().get("leaderId"))).findFirst().orElseThrow();
        List<ClusterEvent> started = events(MpiModule.ID, "COLLECTIVE_STARTED");
        assertThat(elected.sequence()).isLessThan(changed.get(0).sequence());
        assertThat(changed.get(0).sequence()).isLessThan(started.get(started.size() - 1).sequence());
    }
}
