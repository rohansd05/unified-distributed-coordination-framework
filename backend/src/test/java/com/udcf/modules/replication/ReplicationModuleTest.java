package com.udcf.modules.replication;

import com.udcf.core.clock.LamportClock;
import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.cluster.NodeStatus;
import com.udcf.core.cluster.UnknownNodeException;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.replication.dto.AntiEntropyCommand;
import com.udcf.modules.replication.dto.AntiEntropyDto;
import com.udcf.modules.replication.dto.CellDto;
import com.udcf.modules.replication.dto.HealthRowDto;
import com.udcf.modules.replication.dto.InjectStaleCommand;
import com.udcf.modules.replication.dto.InjectionDto;
import com.udcf.modules.replication.dto.NodeReplicaDto;
import com.udcf.modules.replication.dto.ReadDto;
import com.udcf.modules.replication.dto.ReplicaColumnDto;
import com.udcf.modules.replication.dto.ReplicaState;
import com.udcf.modules.replication.dto.ReplicasDto;
import com.udcf.modules.replication.dto.ReplicationOverviewDto;
import com.udcf.modules.replication.dto.ReplicationState;
import com.udcf.modules.replication.dto.WriteCommand;
import com.udcf.modules.replication.dto.WriteDto;
import com.udcf.web.InvalidParameterException;
import com.udcf.web.NodeStateConflictException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The Experiment 5 module on a standalone three-node cluster with real sockets. No Spring
 * context, so crashes here never disturb other tests. No sleeps; Awaitility only waits for
 * asynchronous pushes to end.
 *
 * <p>Test-only port bases 28140 to 28640, so node k's replication port is 2844k: below Linux's
 * ephemeral range and Windows' dynamic range, apart from every other test class.</p>
 */
class ReplicationModuleTest {

    private static final ClusterProperties CLUSTER = new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(28140, 28240, 28340, 28440, 28540, 28640));
    private static final ReplicationProperties PROPERTIES = new ReplicationProperties(50, 2000, 200);
    private static final ReplicationProperties LONG_DELAY = new ReplicationProperties(60_000, 2000, 200);

    private ClusterEventBus bus;
    private Cluster cluster;
    private ReplicationModule module;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), Clock.systemUTC());
        cluster = new Cluster(CLUSTER, bus);
        module = new ReplicationModule(cluster, PROPERTIES, bus, new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    private WriteDto write(String key, String value) {
        return module.write(new WriteCommand(key, value, ConsistencyModel.SYNCHRONOUS));
    }

    private boolean noServiceStarted() {
        return cluster.nodes().stream().allMatch(node -> node.service(ReplicationNodeService.NAME).isEmpty());
    }

    private List<ClusterEvent> moduleEvents() {
        return bus.query(ReplicationModule.ID, null, 5000);
    }

    private NodeReplicaDto node(ReplicationOverviewDto overview, int nodeId) {
        return overview.nodes().stream().filter(n -> n.nodeId() == nodeId).findFirst().orElseThrow();
    }

    private ReplicaState cell(ReplicasDto view, String key, int nodeId) {
        return view.rows().stream().filter(row -> row.key().equals(key)).findFirst().orElseThrow()
                .cells().stream().filter(c -> c.nodeId() == nodeId).map(CellDto::state).findFirst().orElseThrow();
    }

    private ReplicationNodeService service(int nodeId) {
        return ReplicationNodeService.find(cluster.node(nodeId)).orElseThrow();
    }

    // ------------------------------------------------------------------ identity and reads

    @Test
    @DisplayName("id replication, lab 5, title Consistency and Replication, IDLE")
    void identityAndIdle() {
        assertThat(module.id()).isEqualTo("replication");
        assertThat(module.labNumber()).isEqualTo(5);
        assertThat(module.title()).isEqualTo("Consistency and Replication");
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
    }

    @Test
    @DisplayName("overview() on a fresh module starts nothing: no service, selector's choice only, nulls for what is unknown")
    void overviewIsReadOnly() {
        ReplicationOverviewDto overview = module.overview();

        assertThat(noServiceStarted()).isTrue();
        assertThat(overview.primaryNodeId()).isEqualTo(1);
        assertThat(overview.currentPrimaryNodeId()).isNull();
        assertThat(overview.takeoverPending()).isFalse();
        assertThat(overview.lastTakeover()).isNull();
        assertThat(overview.healthMeasuredByNodeId()).isNull();
        assertThat(overview.health()).isEmpty();
        assertThat(overview.latestWrite()).isNull();
        assertThat(overview.nodes()).allSatisfy(n -> {
            assertThat(n.epoch()).isNull();
            assertThat(n.itemCount()).isNull();
            assertThat(n.serviceRunning()).isFalse();
            assertThat(n.actingPrimary()).isFalse();
        });
        assertThat(overview.nodes()).extracting(NodeReplicaDto::role).containsExactly("PRIMARY", "BACKUP", "BACKUP");
        assertThat(overview.asyncDelayMillis()).isEqualTo(50);
        assertThat(overview.asyncDelayReason()).contains("simulated");
        assertThat(overview.models()).filteredOn(m -> m.model() == ConsistencyModel.ASYNCHRONOUS).singleElement()
                .satisfies(m -> assertThat(m.simulated()).isTrue());
        assertThat(overview.conflictRuleNote()).contains("last writer wins").contains("Experiment 8");
    }

    @Test
    @DisplayName("replicas() starts nothing: on a fresh module every replica is UNREACHABLE and there is no reference")
    void replicasViewIsReadOnly() {
        ReplicasDto view = module.replicas();

        assertThat(noServiceStarted()).isTrue();
        assertThat(view.referenceNodeId()).isNull();
        assertThat(view.consistent()).isNull();
        assertThat(view.divergences()).isNull();
        assertThat(view.rows()).isEmpty();
        assertThat(view.replicas()).allSatisfy(column -> {
            assertThat(column.reachable()).isFalse();
            assertThat(column.epoch()).isNull();
            assertThat(column.itemCount()).isNull();
            assertThat(column.error()).contains("ConnectException");
        });
    }

    @Test
    @DisplayName("read() starts nothing: a replica whose service never started is UNREACHABLE, with a null item")
    void readIsReadOnly() {
        ReadDto read = module.read(2, "k");

        assertThat(noServiceStarted()).isTrue();
        assertThat(read.reachable()).isFalse();
        assertThat(read.item()).isNull();
        assertThat(read.state()).isEqualTo(ReplicaState.UNREACHABLE);
        assertThat(read.referenceNodeId()).isNull();
        assertThat(read.error()).contains("ConnectException");
    }

    @Test
    @DisplayName("primary crashed from the cluster page: reads report takeoverPending and change nothing; the next write takes over")
    void primaryCrashFromClusterPageMakesTakeoverPending() {
        write("k", "a");
        cluster.crash(1);   // as the cluster page does

        for (int i = 0; i < 2; i++) {
            ReplicationOverviewDto overview = module.overview();
            module.replicas();
            module.read(2, "k");
            assertThat(overview.primaryNodeId()).isEqualTo(2);
            assertThat(overview.currentPrimaryNodeId()).isEqualTo(1);
            assertThat(overview.takeoverPending()).isTrue();
            assertThat(node(overview, 2).actingPrimary()).isFalse();
            assertThat(node(overview, 2).role()).isEqualTo("PRIMARY");
        }
        assertThat(bus.query(ReplicationModule.ID, 2, 1000)).extracting(ClusterEvent::type)
                .doesNotContain("PRIMARY_SELECTED", "CATCH_UP");

        WriteDto second = write("k", "b");

        assertThat(second.primaryNodeId()).isEqualTo(2);
        assertThat(second.takeover()).isNotNull();
        assertThat(second.takeover().previousPrimaryNodeId()).isEqualTo(1);
        assertThat(second.takeover().newPrimaryNodeId()).isEqualTo(2);
        assertThat(second.takeover().catchUps()).extracting(c -> c.sourceNodeId()).containsExactly(3);
        ReplicationOverviewDto after = module.overview();
        assertThat(after.takeoverPending()).isFalse();
        assertThat(after.currentPrimaryNodeId()).isEqualTo(2);
        assertThat(after.lastTakeover().newPrimaryNodeId()).isEqualTo(2);
    }

    @Test
    @DisplayName("recover() of node 1 runs the pending takeover inside the guarded action: node 1 catches up from 2 and 3")
    void recoverRunsPendingTakeover() {
        write("k", "a");
        cluster.crash(1);
        write("k", "b");                       // node 2 takes over
        write("only-later", "x");

        module.recover(1);

        ReplicationOverviewDto overview = module.overview();
        assertThat(overview.currentPrimaryNodeId()).isEqualTo(1);
        assertThat(overview.takeoverPending()).isFalse();
        assertThat(overview.lastTakeover().previousPrimaryNodeId()).isEqualTo(2);
        assertThat(overview.lastTakeover().catchUps()).extracting(c -> c.sourceNodeId()).containsExactly(2, 3);
        assertThat(overview.lastTakeover().appliedFromCatchUp()).isEqualTo(2);
        assertThat(module.read(1, "k").item().value()).isEqualTo("b");
        assertThat(module.replicas().consistent()).isTrue();
    }

    // ------------------------------------------------------------------ writes

    @Test
    @DisplayName("a synchronous write is COMPLETE with one ACKED push per backup, measured latency and no simulation")
    void syncWriteReturnsCompletePushes() {
        WriteDto dto = write("k", "v;~");

        assertThat(dto.writeId()).hasSize(8);
        assertThat(dto.primaryNodeId()).isEqualTo(1);
        assertThat(dto.item().value()).isEqualTo("v;~");
        assertThat(dto.localResult()).isEqualTo(ApplyResult.APPLIED);
        assertThat(dto.replicationState()).isEqualTo(ReplicationState.COMPLETE);
        assertThat(dto.simulated()).isFalse();
        assertThat(dto.simulatedDelayMillis()).isZero();
        assertThat(dto.simulatedReason()).isNull();
        assertThat(dto.backupNodeIds()).containsExactly(2, 3);
        assertThat(dto.pushes()).allSatisfy(push -> {
            assertThat(push.status()).isEqualTo(PushStatus.ACKED);
            assertThat(push.result()).isEqualTo(ApplyResult.APPLIED);
            assertThat(push.latencyMillis()).isNotNull();
            assertThat(push.detail()).isNull();
        });
        assertThat(dto.takeover()).isNotNull();   // the first selection
        assertThat(dto.takeover().previousPrimaryNodeId()).isNull();
        assertThat(module.overview().latestWrite()).isEqualTo(dto);
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
    }

    @Test
    @DisplayName("ASYNCHRONOUS (60 s simulated delay): PENDING and RUNNING, labelled simulated, a backup reads MISSING; reset drops the pushes")
    void asyncWriteIsPendingStaleAndLabelled() {
        module = new ReplicationModule(cluster, LONG_DELAY, bus, new SimpleMeterRegistry());

        WriteDto dto = module.write(new WriteCommand("k", "v", ConsistencyModel.ASYNCHRONOUS));

        assertThat(dto.replicationState()).isEqualTo(ReplicationState.PENDING);
        assertThat(dto.pushes()).isEmpty();
        assertThat(dto.simulated()).isTrue();
        assertThat(dto.simulatedDelayMillis()).isEqualTo(60_000);
        assertThat(dto.simulatedReason()).contains("simulated");
        assertThat(module.status()).isEqualTo(ModuleStatus.RUNNING);
        ReadDto stale = module.read(2, "k");
        assertThat(stale.state()).isEqualTo(ReplicaState.MISSING);
        assertThat(stale.item()).isNull();
        assertThat(stale.referenceItem().value()).isEqualTo("v");

        module.reset();

        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(module.overview().latestWrite()).isNull();
    }

    @Test
    @DisplayName("ASYNCHRONOUS (50 ms simulated delay): the pushes follow, latestWrite becomes COMPLETE and every backup is CURRENT")
    void asyncWriteCompletesAndConverges() {
        WriteDto dto = module.write(new WriteCommand("k", "v", ConsistencyModel.ASYNCHRONOUS));

        await().atMost(10, TimeUnit.SECONDS).until(() -> module.status() == ModuleStatus.IDLE);
        WriteDto latest = module.overview().latestWrite();
        assertThat(latest.writeId()).isEqualTo(dto.writeId());
        assertThat(latest.replicationState()).isEqualTo(ReplicationState.COMPLETE);
        assertThat(latest.pushes()).extracting(p -> p.status()).containsOnly(PushStatus.ACKED);
        assertThat(module.read(2, "k").state()).isEqualTo(ReplicaState.CURRENT);
        assertThat(module.read(3, "k").state()).isEqualTo(ReplicaState.CURRENT);
    }

    // ------------------------------------------------------------------ guard and errors

    @Test
    @DisplayName("every action takes the guard: write, crash, recover, anti-entropy and stale injection all get ModuleBusyException")
    void everyActionIsGuarded() {
        write("k", "v");
        cluster.crash(3);
        try (ModuleActionGuard.ActionTicket held = module.guard().begin("A test action")) {
            assertThat(module.status()).isEqualTo(ModuleStatus.BUSY);
            assertThat(module.overview().actionInProgress()).isEqualTo("A test action");
            assertThatThrownBy(() -> write("k", "w")).isInstanceOf(ModuleBusyException.class);
            assertThatThrownBy(() -> module.crashBackup(2)).isInstanceOf(ModuleBusyException.class);
            assertThatThrownBy(() -> module.recover(3)).isInstanceOf(ModuleBusyException.class);
            assertThatThrownBy(() -> module.antiEntropy(new AntiEntropyCommand(2))).isInstanceOf(ModuleBusyException.class);
            assertThatThrownBy(() -> module.injectStale(new InjectStaleCommand(2, "k", "old")))
                    .isInstanceOf(ModuleBusyException.class);
        }
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(module.read(1, "k").item().value()).isEqualTo("v");
    }

    @Test
    @DisplayName("invalid parameters are refused before anything happens: no service, no event, no clock tick")
    void invalidParametersChangeNothing() {
        long clock = cluster.clusterClock().current();

        assertThatThrownBy(() -> write("a\nb", "v")).isInstanceOf(InvalidParameterException.class)
                .satisfies(e -> assertThat(((InvalidParameterException) e).parameter()).isEqualTo("key"));
        assertThatThrownBy(() -> write("k", "x".repeat(1025))).isInstanceOf(InvalidParameterException.class)
                .satisfies(e -> assertThat(((InvalidParameterException) e).parameter()).isEqualTo("value"));
        assertThatThrownBy(() -> module.write(new WriteCommand("k", "v", null)))
                .isInstanceOf(InvalidParameterException.class);
        assertThatThrownBy(() -> module.injectStale(new InjectStaleCommand(2, "k", "bad ")))
                .isInstanceOf(InvalidParameterException.class)
                .satisfies(e -> assertThat(((InvalidParameterException) e).parameter()).isEqualTo("staleValue"));
        assertThatThrownBy(() -> module.read(2, " ")).isInstanceOf(InvalidParameterException.class);
        assertThatThrownBy(() -> module.read(9, "k")).isInstanceOf(UnknownNodeException.class);
        assertThatThrownBy(() -> module.crashBackup(9)).isInstanceOf(UnknownNodeException.class);
        assertThatThrownBy(() -> module.recover(9)).isInstanceOf(UnknownNodeException.class);
        assertThatThrownBy(() -> module.antiEntropy(new AntiEntropyCommand(9))).isInstanceOf(UnknownNodeException.class);
        assertThatThrownBy(() -> module.injectStale(new InjectStaleCommand(9, "k", "old")))
                .isInstanceOf(UnknownNodeException.class);

        assertThat(noServiceStarted()).isTrue();
        assertThat(moduleEvents()).isEmpty();
        assertThat(cluster.clusterClock().current()).isEqualTo(clock);
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
    }

    @Test
    @DisplayName("state errors: primary as a target (400), crashed target (409 node down), node already in that state (409)")
    void stateErrorsAreRefused() {
        write("k", "v");
        cluster.crash(3);

        assertThatThrownBy(() -> module.crashBackup(1)).isInstanceOf(InvalidParameterException.class)
                .hasMessageContaining("Cluster page");
        assertThatThrownBy(() -> module.crashBackup(3)).isInstanceOf(NodeStateConflictException.class);
        assertThatThrownBy(() -> module.recover(2)).isInstanceOf(NodeStateConflictException.class);
        assertThatThrownBy(() -> module.antiEntropy(new AntiEntropyCommand(1))).isInstanceOf(InvalidParameterException.class);
        assertThatThrownBy(() -> module.antiEntropy(new AntiEntropyCommand(3))).isInstanceOf(NodeDownException.class);
        assertThatThrownBy(() -> module.injectStale(new InjectStaleCommand(3, "k", "old")))
                .isInstanceOf(NodeDownException.class);
        assertThatThrownBy(() -> module.injectStale(new InjectStaleCommand(1, "k", "old")))
                .isInstanceOf(InvalidParameterException.class);
        assertThatThrownBy(() -> module.injectStale(new InjectStaleCommand(2, "missing", "old")))
                .isInstanceOf(InvalidParameterException.class).hasMessageContaining("does not hold key");
        cluster.crash(1);
        cluster.crash(2);
        assertThatThrownBy(() -> write("k", "w")).isInstanceOf(NodeStateConflictException.class)
                .hasMessageContaining("Every node is crashed");
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
    }

    // ------------------------------------------------------------------ the experiment's "done when"

    @Test
    @DisplayName("read states per replica: CURRENT, STALE, MISSING, ABSENT, and UNREACHABLE with a null item")
    void readStatesPerReplica() {
        write("k1", "v1");
        module.crashBackup(3);
        write("k1", "v2");
        write("k2", "x");
        module.recover(3);

        assertThat(module.read(1, "k1").state()).isEqualTo(ReplicaState.CURRENT);
        assertThat(module.read(2, "k1").state()).isEqualTo(ReplicaState.CURRENT);
        ReadDto stale = module.read(3, "k1");
        assertThat(stale.state()).isEqualTo(ReplicaState.STALE);
        assertThat(stale.item().value()).isEqualTo("v1");
        assertThat(stale.referenceItem().value()).isEqualTo("v2");
        assertThat(module.read(3, "k2").state()).isEqualTo(ReplicaState.MISSING);
        assertThat(module.read(2, "nothing").state()).isEqualTo(ReplicaState.ABSENT);
        module.crashBackup(2);
        ReadDto unreachable = module.read(2, "k1");
        assertThat(unreachable.state()).isEqualTo(ReplicaState.UNREACHABLE);
        assertThat(unreachable.reachable()).isFalse();
        assertThat(unreachable.item()).isNull();
        assertThat(unreachable.error()).contains("ConnectException");
    }

    @Test
    @DisplayName("done when: a crashed backup diverges and converges again through anti-entropy")
    void crashedBackupDivergesThenConvergesThroughAntiEntropy() {
        write("k1", "a");
        module.crashBackup(3);
        write("k2", "b");
        write("k3", "c");

        ReplicasDto down = module.replicas();
        assertThat(down.replicas()).filteredOn(c -> c.nodeId() == 3).singleElement()
                .satisfies(c -> assertThat(c.reachable()).isFalse());
        assertThat(cell(down, "k2", 3)).isEqualTo(ReplicaState.UNREACHABLE);
        assertThat(down.consistent()).isTrue();   // every reachable replica agrees; node 3 is left out

        module.recover(3);
        ReplicasDto diverged = module.replicas();
        assertThat(diverged.consistent()).isFalse();
        assertThat(diverged.divergences()).isEqualTo(2);
        assertThat(cell(diverged, "k2", 3)).isEqualTo(ReplicaState.MISSING);
        assertThat(cell(diverged, "k1", 3)).isEqualTo(ReplicaState.CURRENT);

        AntiEntropyDto report = module.antiEntropy(new AntiEntropyCommand(3));

        assertThat(report.completed()).isTrue();
        assertThat(report.sourceNodeId()).isEqualTo(1);
        assertThat(report.applied()).isEqualTo(2);
        assertThat(report.failure()).isNull();
        ReplicasDto converged = module.replicas();
        assertThat(converged.consistent()).isTrue();
        assertThat(converged.divergences()).isZero();
        assertThat(module.overview().latestAntiEntropy()).isEqualTo(report);
    }

    @Test
    @DisplayName("done when: a stale out-of-order update is rejected; the backup keeps the newer value")
    void staleInjectionIsRejected() {
        write("k", "new");

        InjectionDto injection = module.injectStale(new InjectStaleCommand(2, "k", "old"));

        assertThat(injection.rejected()).isTrue();
        assertThat(injection.push().result()).isEqualTo(ApplyResult.STALE);
        assertThat(injection.staleItem().lamportTime()).isEqualTo(injection.currentItem().lamportTime() - 1);
        assertThat(module.read(2, "k").item().value()).isEqualTo("new");
        assertThat(module.read(2, "k").state()).isEqualTo(ReplicaState.CURRENT);
        HealthRowDto row = module.overview().health().stream().filter(r -> r.backupNodeId() == 2).findFirst().orElseThrow();
        assertThat(row.staleRejections()).isEqualTo(1);
        assertThat(module.overview().latestInjection()).isEqualTo(injection);
    }

    @Test
    @DisplayName("the health table is measured by the current primary: real counts, nulls before the first acknowledgement, fresh after a takeover")
    void healthIsMeasuredByCurrentPrimaryWithNulls() {
        module.crashBackup(3);
        write("k", "v");

        ReplicationOverviewDto overview = module.overview();
        assertThat(overview.healthMeasuredByNodeId()).isEqualTo(1);
        assertThat(overview.health()).extracting(HealthRowDto::backupNodeId).containsExactly(2, 3);
        HealthRowDto down = overview.health().get(1);
        assertThat(down.acks()).isZero();
        assertThat(down.failures()).isEqualTo(1);
        assertThat(down.averageLatencyMillis()).isNull();
        assertThat(down.maxLatencyMillis()).isNull();
        assertThat(down.lastSync()).isNull();
        assertThat(overview.health().get(0).averageLatencyMillis()).isNotNull();

        module.recover(3);
        cluster.crash(1);
        write("k", "w");   // node 2 takes over; its table starts afresh
        ReplicationOverviewDto after = module.overview();
        assertThat(after.healthMeasuredByNodeId()).isEqualTo(2);
        assertThat(after.health()).extracting(HealthRowDto::backupNodeId).containsExactly(1, 3);
        assertThat(after.health().get(0).failures()).isEqualTo(1);   // node 1 is down
        assertThat(after.health().get(1).acks()).isEqualTo(1);
    }

    @Test
    @DisplayName("crash and recover go through the core path: cluster events, node status and the replication port stay in step")
    void crashAndRecoverGoThroughClusterCore() {
        write("k", "v");

        NodeReplicaDto crashed = module.crashBackup(3);

        assertThat(crashed.nodeStatus()).isEqualTo(NodeStatus.CRASHED);
        assertThat(crashed.serviceRunning()).isFalse();
        assertThat(crashed.itemCount()).isNull();
        assertThat(cluster.node(3).status()).isEqualTo(NodeStatus.CRASHED);
        assertThat(bus.query("cluster", 3, 100)).extracting(ClusterEvent::type).contains("NODE_CRASHED");
        assertThatThrownBy(() -> new ReplicationClient(2000).read(cluster.node(3).ports().replication(), 0,
                new LamportClock(), "k")).isInstanceOf(ConnectException.class);

        NodeReplicaDto recovered = module.recover(3);

        assertThat(recovered.nodeStatus()).isEqualTo(NodeStatus.UP);
        assertThat(recovered.serviceRunning()).isTrue();
        assertThat(recovered.itemCount()).isEqualTo(1);
        assertThat(bus.query("cluster", 3, 100)).extracting(ClusterEvent::type).contains("NODE_RECOVERED");
        assertThat(cluster.node(3).runningServices()).contains(ReplicationNodeService.NAME);
    }

    // ------------------------------------------------------------------ reset

    @Test
    @DisplayName("reset is a clean slate: stores, stats, roles, epochs and the module's own history cleared; services still serve")
    void resetIsCleanSlate() {
        write("k", "v");
        module.injectStale(new InjectStaleCommand(2, "k", "old"));
        module.antiEntropy(new AntiEntropyCommand(3));
        service(1).observeEpoch(4);

        module.reset();

        ReplicationOverviewDto overview = module.overview();
        assertThat(overview.latestWrite()).isNull();
        assertThat(overview.latestAntiEntropy()).isNull();
        assertThat(overview.latestInjection()).isNull();
        assertThat(overview.lastTakeover()).isNull();
        assertThat(overview.currentPrimaryNodeId()).isNull();
        assertThat(overview.takeoverPending()).isFalse();
        assertThat(overview.health()).isEmpty();
        assertThat(overview.nodes()).allSatisfy(n -> {
            assertThat(n.serviceRunning()).isTrue();
            assertThat(n.actingPrimary()).isFalse();
            assertThat(n.epoch()).isEqualTo(DataStore.INITIAL_EPOCH);
            assertThat(n.itemCount()).isZero();
        });
        for (ClusterNode node : cluster.nodes()) {
            assertThat(service(node.id()).stats()).isEmpty();
        }
        assertThat(module.replicas().rows()).isEmpty();
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
        WriteDto again = write("k2", "v");
        assertThat(again.takeover().previousPrimaryNodeId()).isNull();
    }

    @Test
    @DisplayName("reset with a crashed backup: its state is cleared and it stays closed (nothing listens); recovery is the cluster's call")
    void resetWithCrashedBackupLeavesItClosedAndClean() {
        write("k", "v");
        module.crashBackup(3);

        module.reset();

        assertThat(cluster.node(3).status()).isEqualTo(NodeStatus.CRASHED);
        assertThat(service(3).isRunning()).isFalse();
        assertThat(service(3).isPrimary()).isFalse();
        assertThat(service(3).epoch()).isEqualTo(DataStore.INITIAL_EPOCH);
        assertThat(cluster.node(3).runningServices()).isEmpty();
        assertThatThrownBy(() -> new ReplicationClient(2000).read(cluster.node(3).ports().replication(), 0,
                new LamportClock(), "k")).isInstanceOf(ConnectException.class);
        assertThat(module.replicas().replicas()).filteredOn(c -> c.nodeId() == 3)
                .extracting(ReplicaColumnDto::reachable).containsExactly(false);

        assertThat(cluster.recover(3)).isTrue();
        assertThat(service(3).snapshot()).isEmpty();
    }

    @Test
    @DisplayName("reset twice in a row: on a fresh module it starts nothing; after a write both resets leave a clean slate")
    void resetTwiceInARow() {
        module.reset();
        module.reset();
        assertThat(noServiceStarted()).isTrue();

        write("k", "v");
        module.reset();
        module.reset();

        assertThat(module.overview().nodes()).allSatisfy(n -> assertThat(n.itemCount()).isZero());
        assertThat(module.overview().currentPrimaryNodeId()).isNull();
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(write("k", "w").item().lamportTime()).isPositive();
    }
}
