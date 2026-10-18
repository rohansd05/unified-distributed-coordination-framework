package com.udcf.modules.mpi;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.LeaderElector;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.cluster.RoleProvider;
import com.udcf.core.cluster.UnknownNodeException;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.metrics.MetricNames;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.mpi.dto.CollectiveCommand;
import com.udcf.modules.mpi.dto.CollectiveDto;
import com.udcf.modules.mpi.dto.MpiLimitsDto;
import com.udcf.modules.mpi.dto.MpiNodeDto;
import com.udcf.modules.mpi.dto.MpiOverviewDto;
import com.udcf.modules.mpi.dto.OperationDto;
import com.udcf.modules.mpi.dto.RootSource;
import com.udcf.modules.mpi.dto.TransferDto;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.InvalidCallException;
import com.udcf.modules.rmi.PayloadSizer;
import com.udcf.modules.rmi.RemoteFailureKind;
import com.udcf.modules.rmi.RmiModule;
import com.udcf.modules.rmi.RmiProperties;
import com.udcf.modules.rmi.RmiTestSupport;
import com.udcf.modules.rmi.RmiTransportProperties;
import com.udcf.modules.rmi.ScriptedNanoClock;
import com.udcf.modules.rmi.dto.RmiNodeDto;
import com.udcf.modules.rmi.dto.RmiNodeState;
import com.udcf.modules.rmi.dto.RmiOverviewDto;
import com.udcf.web.NodeStateConflictException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

import java.rmi.ConnectException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Experiment 9 module with no network: a Mockito RmiModule (so ensureStarted binds nothing), E9a's
 * ScriptedCollectiveTransport (a real endpoint logic per node) and scripted time. Outcomes, the root
 * rule, ensure-started order, busy rejection, the guard and history slot on an unexpected failure,
 * the history bound, reset, nulls, events and validation.
 *
 * <p>No socket and no port: the cluster's bases (RMI 28695, the rest production values) are never
 * bound, because no service is started.</p>
 */
class MpiModuleTest {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final RmiProperties RMI = RmiTestSupport.PROPERTIES;
    private static final RmiTransportProperties RMI_TRANSPORT = RmiTestSupport.TRANSPORT;

    private ClusterEventBus bus;
    private Cluster cluster;
    private RmiModule rmi;
    private SimpleMeterRegistry registry;
    private ScriptedCollectiveTransport transport;
    private RoleProvider roles;
    private MpiModule module;

    @BeforeEach
    void setUp() {
        bus = RmiTestSupport.bus();
        cluster = new Cluster(new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
                new ClusterProperties.Ports(28695, 6000, 7000, 7100, 7200, 7300)), bus);
        rmi = mock(RmiModule.class);
        when(rmi.overview()).thenAnswer(invocation -> rmiOverview());
        registry = new SimpleMeterRegistry();
        transport = MpiTestResults.transport();
        roles = new RoleProvider(cluster, (LeaderElector) null);   // no election module: no leader means 409 at once
        cluster.assignLeader(1);
        module = newModule(MpiTestResults.PROPERTIES, RMI, transport, MpiTestResults.stepping());
    }

    @AfterEach
    void tearDown() {
        module.close();
        roles.close();
        cluster.close();
        bus.close();
    }

    private MpiModule newModule(MpiProperties properties, RmiProperties rmiProperties, CollectiveTransport collectiveTransport,
                                LongSupplier nanoClock) {
        return new MpiModule(cluster, bus, properties, rmi, rmiProperties, RMI_TRANSPORT, registry,
                Clock.fixed(NOW, ZoneOffset.UTC), collectiveTransport, nanoClock, roles);
    }

    /** What Experiment 1 reports: no node started; node 2 with a start failure. */
    private RmiOverviewDto rmiOverview() {
        List<RmiNodeDto> nodes = cluster.nodes().stream().map(node -> new RmiNodeDto(node.id(), node.status(),
                node.isUp() ? RmiNodeState.NOT_STARTED : RmiNodeState.CRASHED, node.ports().rmi(),
                node.id() == 2 ? "port in use" : null)).toList();
        return new RmiOverviewDto(ModuleStatus.IDLE, null, "NodeEndpoint", nodes, List.of(), null, null, null, null, null,
                List.of(), null);
    }

    private static <T extends Throwable> T thrown(Class<T> type, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        T caught = catchThrowableOfType(call, type);
        assertThat(caught).as("expected %s", type.getSimpleName()).isNotNull();
        return caught;
    }

    private static CollectiveCommand command(CollectiveOperation operation, int count) {
        return new CollectiveCommand(operation, count, null, null);
    }

    private List<ClusterEvent> events(String type) {
        return bus.query(MpiModule.ID, null, 500).stream().filter(e -> e.type().equals(type)).toList();
    }

    private static TransferDto toNode(CollectiveDto dto, int nodeId) {
        return dto.transfers().stream().filter(t -> t.toNodeId() == nodeId || t.fromNodeId() == nodeId)
                .filter(t -> t.status() != TransferStatus.LOCAL).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ outcomes

    @ParameterizedTest
    @EnumSource(CollectiveOperation.class)
    @DisplayName("each operation COMPLETEs over every live node, verified, with the seed defaulting to the id")
    void complete(CollectiveOperation operation) {
        CollectiveDto dto = module.run(command(operation, 10));

        assertThat(dto.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(dto.id()).isEqualTo(1);
        assertThat(dto.seed()).isEqualTo(1);
        assertThat(dto.startedAt()).isEqualTo(NOW);
        assertThat(dto.rootNodeId()).isEqualTo(1);
        assertThat(dto.rootSource()).isEqualTo(RootSource.LEADER);
        assertThat(dto.rankNodeIds()).containsExactly(1, 2, 3);
        assertThat(dto.verification().passed()).isTrue();
        assertThat(dto.transfers()).extracting(TransferDto::status)
                .containsExactly(TransferStatus.LOCAL, TransferStatus.DELIVERED, TransferStatus.DELIVERED);
        assertThat(module.latest()).isEqualTo(dto);
        assertThat(module.collective(1)).isEqualTo(dto);
        assertThat(transport.node(2).storedIds()).isEqualTo(operation == CollectiveOperation.GATHER ? List.of() : List.of(1L));
    }

    @Test
    @DisplayName("a node refused at the lookup: PARTIAL, its transfer FAILED at LOOKUP with only a time to failure; unknowns stay null")
    void partial() {
        transport.failing(3, CallStage.LOOKUP, new ConnectException("Connection refused to host: 127.0.0.1"));

        CollectiveDto dto = module.run(command(CollectiveOperation.BROADCAST, 10));

        assertThat(dto.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
        TransferDto failed = toNode(dto, 3);
        assertThat(failed.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(failed.failureStage()).isEqualTo(CallStage.LOOKUP);
        assertThat(failed.failureKind()).isEqualTo(RemoteFailureKind.CONNECTION_REFUSED);
        assertThat(failed.timeToFailureNanos()).isPositive();
        assertThat(failed.latencyNanos()).isNull();
        assertThat(failed.requestBytes()).isNull();
        assertThat(failed.replyBytes()).isNull();
        assertThat(failed.senderLamportAtSend()).isNull();
        assertThat(failed.replyLamport()).isNull();
        assertThat(failed.lamportAfterReply()).isNull();
        assertThat(failed.verified()).isNull();
        assertThat(dto.verification().passed()).isNull();
        assertThat(events("COLLECTIVE_FINISHED").get(0).data()).containsEntry("outcome", "PARTIAL")
                .containsEntry("delivered", 1L).containsEntry("failed", 1L);
    }

    @Test
    @DisplayName("every remote transfer failing: FAILED")
    void failed() {
        transport.failing(2, CallStage.CALL, new java.rmi.UnmarshalException("lost"))
                .failing(3, CallStage.LOOKUP, new ConnectException("refused"));

        assertThat(module.run(command(CollectiveOperation.GATHER, 10)).outcome()).isEqualTo(CollectiveOutcome.FAILED);
    }

    @Test
    @DisplayName("the deadline passing: the later transfer is NOT_ATTEMPTED with a reason and no number")
    void notAttempted() {
        long late = (MpiTestResults.PROPERTIES.collectiveDeadlineMillis() + 1) * 1_000_000L;
        module = newModule(MpiTestResults.PROPERTIES, RMI, transport, new ScriptedNanoClock(0, 0, 0, 100, 100, late));

        CollectiveDto dto = module.run(command(CollectiveOperation.BROADCAST, 10));

        TransferDto skipped = toNode(dto, 3);
        assertThat(dto.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
        assertThat(skipped.status()).isEqualTo(TransferStatus.NOT_ATTEMPTED);
        assertThat(skipped.notAttemptedReason()).isNotBlank();
        assertThat(skipped.latencyNanos()).isNull();
        assertThat(skipped.timeToFailureNanos()).isNull();
        assertThat(skipped.requestBytes()).isNull();
    }

    @Test
    @DisplayName("count below the live nodes for scatter: 200 REFUSED, kept in the history, COLLECTIVE_REFUSED and the metric")
    void refused() {
        CollectiveDto dto = module.run(command(CollectiveOperation.SCATTER, 2));

        assertThat(dto.outcome()).isEqualTo(CollectiveOutcome.REFUSED);
        assertThat(dto.refusal().reason()).isEqualTo(RefusalReason.COUNT_BELOW_PARTICIPANTS);
        assertThat(dto.transfers()).isEmpty();
        assertThat(module.history()).containsExactly(dto);
        assertThat(transport.sent()).isEmpty();
        List<ClusterEvent> refusedEvents = events("COLLECTIVE_REFUSED");
        assertThat(refusedEvents).hasSize(1);
        assertThat(refusedEvents.get(0).nodeId()).isEqualTo(1);
        assertThat(refusedEvents.get(0).data()).containsEntry("reason", "COUNT_BELOW_PARTICIPANTS").containsEntry("count", 2);
        assertThat(events("COLLECTIVE_FINISHED")).isEmpty();
        assertThat(registry.find(MetricNames.MPI_COLLECTIVES_TOTAL).tags("outcome", "REFUSED").counter().count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ RMI and root

    @Test
    @DisplayName("RMI is started (ensureStarted) before the first transfer; start() calls it too")
    void ensureStartedFirst() throws Exception {
        ScriptedCollectiveTransport spied = spy(MpiTestResults.transport());
        module = newModule(MpiTestResults.PROPERTIES, RMI, spied, MpiTestResults.stepping());

        module.run(command(CollectiveOperation.BROADCAST, 10));

        InOrder order = inOrder(rmi, spied);
        order.verify(rmi).ensureStarted();
        order.verify(spied, atLeastOnce()).deliver(anyInt(), any(), anyLong(), anyInt(), any());

        MpiOverviewDto overview = module.start();
        verify(rmi, org.mockito.Mockito.times(2)).ensureStarted();
        assertThat(overview.status()).isEqualTo(ModuleStatus.IDLE);
    }

    /** Waits until the roles dispatcher has delivered every leader change announced so far. */
    private void deliverLeaderChanges() throws InterruptedException {
        CountDownLatch delivered = new CountDownLatch(1);
        roles.afterPendingChanges(delivered::countDown);
        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("default root: the leader (LEADER); after the leader crashes and node 2 is elected, node 2, the crashed node skipped")
    void defaultRoot() {
        cluster.assignLeader(3);
        CollectiveDto led = module.run(command(CollectiveOperation.BROADCAST, 10));
        assertThat(led.rootNodeId()).isEqualTo(3);
        assertThat(led.rootSource()).isEqualTo(RootSource.LEADER);
        assertThat(led.rootRank()).isEqualTo(2);

        cluster.crash(3);
        cluster.assignLeader(2);
        CollectiveDto next = module.run(command(CollectiveOperation.BROADCAST, 10));
        assertThat(next.rootNodeId()).isEqualTo(2);
        assertThat(next.rootSource()).isEqualTo(RootSource.LEADER);
        assertThat(next.skippedNodeIds()).containsExactly(3);
        assertThat(next.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
    }

    @Test
    @DisplayName("no leader could be elected: NoLeaderException (409 NO_LEADER) before the guard; nothing started or recorded")
    void noLeader() {
        cluster.assignLeader(null);

        assertThatThrownBy(() -> module.run(command(CollectiveOperation.BROADCAST, 10)))
                .isInstanceOf(NoLeaderException.class).hasMessageStartingWith("No leader could be elected, so a collective");
        verify(rmi, never()).ensureStarted();
        assertThat(module.history()).isEmpty();
        assertThat(events("COLLECTIVE_STARTED")).isEmpty();
        assertThat(module.overview().defaultRootNodeId()).isNull();
        assertThat(module.overview().defaultRootSource()).isNull();
    }

    @Test
    @DisplayName("MPI_ROOT_CHANGED: the first root after a reset is silent; a crashed leader's successor publishes once, before the next start")
    void rootChangedAfterLeaderCrash() throws InterruptedException {
        module.run(command(CollectiveOperation.BROADCAST, 10));   // remembers node 1, silently
        assertThat(events("MPI_ROOT_CHANGED")).isEmpty();

        cluster.crash(1);
        cluster.assignLeader(3);
        deliverLeaderChanges();
        module.run(command(CollectiveOperation.BROADCAST, 10));
        deliverLeaderChanges();

        List<ClusterEvent> changed = events("MPI_ROOT_CHANGED");
        assertThat(changed).hasSize(1);
        ClusterEvent event = changed.get(0);
        assertThat(event.nodeId()).isEqualTo(3);
        assertThat(event.peerId()).isEqualTo(1);
        assertThat(event.data()).containsEntry("nodeId", 3).containsEntry("previousNodeId", 1)
                .containsEntry("cause", "LEADER_CRASHED");
        assertThat(event.message()).isEqualTo("Node 3 is the MPI root for the next collective: node 1 crashed and "
                + "Experiment 4 elected node 3.");
        long lastStarted = events("COLLECTIVE_STARTED").stream().mapToLong(ClusterEvent::sequence).max().orElseThrow();
        assertThat(event.sequence()).isLessThan(lastStarted);   // bus order: the change precedes the next start
    }

    @Test
    @DisplayName("MPI_ROOT_CHANGED with cause ELECTED when the previous root is still up; the next collective adds nothing")
    void rootChangedWhileUp() throws InterruptedException {
        module.run(command(CollectiveOperation.BROADCAST, 10));
        cluster.assignLeader(2);
        deliverLeaderChanges();

        assertThat(events("MPI_ROOT_CHANGED")).singleElement().satisfies(event -> {
            assertThat(event.nodeId()).isEqualTo(2);
            assertThat(event.data()).containsEntry("previousNodeId", 1).containsEntry("cause", "ELECTED");
            assertThat(event.message()).endsWith("Experiment 4 elected node 2 in place of node 1.");
        });
        module.run(command(CollectiveOperation.BROADCAST, 10));
        assertThat(events("MPI_ROOT_CHANGED")).hasSize(1);
    }

    @Test
    @DisplayName("MPI_ROOT_CHANGED is not published without a default-root collective since the last reset")
    void rootChangedNeedsAnAction() throws InterruptedException {
        module.run(new CollectiveCommand(CollectiveOperation.BROADCAST, 10, null, 2));   // a requested root
        cluster.assignLeader(3);
        deliverLeaderChanges();
        assertThat(events("MPI_ROOT_CHANGED")).isEmpty();

        module.run(command(CollectiveOperation.BROADCAST, 10));   // remembers node 3
        module.reset();
        cluster.assignLeader(2);
        deliverLeaderChanges();
        assertThat(events("MPI_ROOT_CHANGED")).isEmpty();
    }

    @Test
    @DisplayName("a requested root wins over the leader (REQUESTED)")
    void requestedRoot() {
        cluster.assignLeader(3);

        CollectiveDto dto = module.run(new CollectiveCommand(CollectiveOperation.SCATTER, 10, 7L, 2));

        assertThat(dto.rootNodeId()).isEqualTo(2);
        assertThat(dto.rootSource()).isEqualTo(RootSource.REQUESTED);
        assertThat(dto.rootRank()).isEqualTo(1);
        assertThat(dto.seed()).isEqualTo(7);
    }

    @Test
    @DisplayName("a crashed requested root: NodeDownException (409), nothing started, recorded or held")
    void requestedRootDown() {
        cluster.crash(2);

        NodeDownException down = thrown(NodeDownException.class,
                () -> module.run(new CollectiveCommand(CollectiveOperation.BROADCAST, 10, null, 2)));

        assertThat(down.nodeId()).isEqualTo(2);
        verify(rmi, never()).ensureStarted();
        assertThat(module.history()).isEmpty();
        assertThat(module.status()).isNotEqualTo(ModuleStatus.BUSY);
    }

    @Test
    @DisplayName("an unknown requested root: UnknownNodeException (404) before the guard is taken")
    void unknownRoot() {
        assertThatThrownBy(() -> module.run(new CollectiveCommand(CollectiveOperation.BROADCAST, 10, null, 9)))
                .isInstanceOf(UnknownNodeException.class);
        verify(rmi, never()).ensureStarted();
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
    }

    @Test
    @DisplayName("every node crashed and no root requested: NodeStateConflictException for node 0 (409)")
    void everyNodeCrashed() {
        cluster.crash(1);
        cluster.crash(2);
        cluster.crash(3);

        NodeStateConflictException conflict = thrown(NodeStateConflictException.class,
                () -> module.run(command(CollectiveOperation.BROADCAST, 10)));

        assertThat(conflict.nodeId()).isZero();
        assertThat(module.history()).isEmpty();
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
    }

    // ------------------------------------------------------------------ guard and history

    @Test
    @DisplayName("busy: a collective and a start are rejected (409, not queued) with ACTION_REJECTED_BUSY; nothing runs")
    void busy() {
        try (ModuleActionGuard.ActionTicket held = module.guard().begin("held by the test")) {
            assertThat(module.status()).isEqualTo(ModuleStatus.BUSY);
            ModuleBusyException busy = thrown(ModuleBusyException.class,
                    () -> module.run(command(CollectiveOperation.BROADCAST, 10)));
            assertThat(busy.actionInProgress()).isEqualTo("held by the test");
            assertThatThrownBy(() -> module.start()).isInstanceOf(ModuleBusyException.class);
            assertThat(module.overview().actionInProgress()).isEqualTo("held by the test");
        }

        List<ClusterEvent> rejected = events("ACTION_REJECTED_BUSY");
        assertThat(rejected).hasSize(2);
        assertThat(rejected.get(0).nodeId()).isZero();
        assertThat(rejected.get(0).data()).containsEntry("requested", "BROADCAST of 10 values")
                .containsEntry("actionInProgress", "held by the test");
        verify(rmi, never()).ensureStarted();
        assertThat(transport.sent()).isEmpty();
        assertThat(module.history()).isEmpty();
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
    }

    @Test
    @DisplayName("the executor throwing unexpectedly: the guard is released and the reserved id is given back")
    void unexpectedFailure() {
        // the timer's end reading (50) is before its start (100): the executor's own IllegalStateException
        module = newModule(MpiTestResults.PROPERTIES, RMI, transport, new ScriptedNanoClock(100, 100, 100, 50));

        assertThatThrownBy(() -> module.run(command(CollectiveOperation.BROADCAST, 10)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("backwards");

        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(module.history()).isEmpty();
        assertThat(module.run(command(CollectiveOperation.BROADCAST, 10)).id()).isEqualTo(1);
    }

    @Test
    @DisplayName("the history keeps history-size collectives, newest first; an older id is 404")
    void historyBounded() {
        module = newModule(new MpiProperties(100_000, 10_000, 2, 8, 300), RMI, transport, MpiTestResults.stepping());

        module.run(command(CollectiveOperation.BROADCAST, 10));
        module.run(command(CollectiveOperation.SCATTER, 10));
        module.run(command(CollectiveOperation.GATHER, 10));

        assertThat(module.history()).extracting(CollectiveDto::id).containsExactly(3L, 2L);
        assertThat(thrown(UnknownCollectiveException.class, () -> module.collective(1)).collectiveId())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("reset forgets every collective and restarts ids at 1; latest is then 404 with a null id")
    void reset() {
        module.run(command(CollectiveOperation.BROADCAST, 10));

        module.reset();

        assertThat(module.history()).isEmpty();
        assertThat(thrown(UnknownCollectiveException.class, () -> module.latest()).collectiveId()).isNull();
        assertThat(module.run(command(CollectiveOperation.BROADCAST, 10)).id()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ events

    @Test
    @DisplayName("events: STARTED at the root's time before the first send, FINISHED at its time after; no event ticks a clock")
    void eventsAndLamport() {
        long before = cluster.node(1).clock().current();

        CollectiveDto dto = module.run(command(CollectiveOperation.BROADCAST, 10));

        ClusterEvent started = events("COLLECTIVE_STARTED").get(0);
        ClusterEvent finished = events("COLLECTIVE_FINISHED").get(0);
        assertThat(started.sequence()).isLessThan(finished.sequence());
        assertThat(started.nodeId()).isEqualTo(1);
        assertThat(started.lamportTime()).isEqualTo(before);
        assertThat(dto.transfers().get(1).senderLamportAtSend()).isEqualTo(before + 1);
        long after = cluster.node(1).clock().current();
        assertThat(dto.transfers().get(2).lamportAfterReply()).isEqualTo(after);
        assertThat(finished.lamportTime()).isEqualTo(after);
        assertThat(started.data()).containsEntry("collectiveId", 1L).containsEntry("operation", "BROADCAST")
                .containsEntry("count", 10).containsEntry("seed", 1L).containsEntry("rootSource", "LEADER")
                .containsEntry("liveNodeIds", List.of(1, 2, 3));
        assertThat(finished.data()).containsEntry("outcome", "COMPLETE").containsEntry("delivered", 2L)
                .containsEntry("notAttempted", 0L).containsEntry("totalNanos", dto.totalNanos())
                .containsEntry("passed", true).doesNotContainKey("latencyMillis");
        assertThat(finished.message()).doesNotContain("COMPLETE").contains("complete");
    }

    // ------------------------------------------------------------------ overview and limits

    @Test
    @DisplayName("overview: participants from Experiment 1's view plus leader and endpoint, default root, operations, texts")
    void overview() {
        cluster.assignLeader(2);

        MpiOverviewDto overview = module.overview();

        assertThat(overview.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(overview.actionInProgress()).isNull();
        assertThat(overview.boundName()).isEqualTo("CollectiveEndpoint");
        assertThat(overview.defaultRootNodeId()).isEqualTo(2);
        assertThat(overview.defaultRootSource()).isEqualTo(RootSource.LEADER);
        assertThat(overview.participants()).extracting(MpiNodeDto::nodeId).containsExactly(1, 2, 3);
        MpiNodeDto two = overview.participants().get(1);
        assertThat(two.rmiState()).isEqualTo(RmiNodeState.NOT_STARTED);
        assertThat(two.startFailure()).isEqualTo("port in use");
        assertThat(two.registryPort()).isEqualTo(28697);
        assertThat(two.leader()).isTrue();
        assertThat(two.collectiveEndpointExported()).isFalse();
        assertThat(two.storedCollectiveIds()).isNull();
        assertThat(overview.operations()).extracting(OperationDto::operation, OperationDto::minimumCount).containsExactly(
                org.assertj.core.groups.Tuple.tuple(CollectiveOperation.BROADCAST, 1),
                org.assertj.core.groups.Tuple.tuple(CollectiveOperation.SCATTER, 3),
                org.assertj.core.groups.Tuple.tuple(CollectiveOperation.GATHER, 3));
        assertThat(overview.latencyBasis()).isEqualTo(RmiCollectiveTransport.LATENCY_BASIS);
        assertThat(overview.payloadSizeBasis()).isEqualTo(PayloadSizer.LABEL);
        assertThat(overview.coHosting().simulated()).isTrue();
        assertThat(overview.algorithmNote()).contains("linear");
        assertThat(overview.latest()).isNull();

        cluster.crash(1);
        cluster.crash(2);
        cluster.crash(3);
        MpiOverviewDto down = module.overview();
        assertThat(down.defaultRootNodeId()).isNull();
        assertThat(down.defaultRootSource()).isNull();
        assertThat(down.operations().get(1).minimumCount()).isNull();
    }

    @Test
    @DisplayName("limits come from the properties; the worst case is 3 x (connect + call) and the recommendation adds the deadline exactly")
    void limitsFromProperties() {
        MpiLimitsDto local = module.overview().limits();
        assertThat(local.maxPayloadElements()).isEqualTo(100_000);
        assertThat(local.collectiveDeadlineMillis()).isEqualTo(10_000);
        assertThat(local.historySize()).isEqualTo(20);
        assertThat(local.maxSeed()).isEqualTo(9_007_199_254_740_991L);
        assertThat(local.connectTimeoutMillis()).isEqualTo(RMI_TRANSPORT.connectTimeoutMillis());
        assertThat(local.callTimeoutMillis()).isEqualTo(RMI.callTimeoutMillis());
        assertThat(local.worstCaseTransferMillis())
                .isEqualTo(3L * (RMI_TRANSPORT.connectTimeoutMillis() + RMI.callTimeoutMillis()));
        assertThat(local.recommendedMinClientTimeoutMillis()).isEqualTo(10_000 + local.worstCaseTransferMillis());

        MpiLimitsDto other = newModule(new MpiProperties(500, 2_000, 3, 8, 300), new RmiProperties(10, 10, 5, 700, 300),
                transport, MpiTestResults.stepping()).limits();
        assertThat(other.worstCaseTransferMillis()).isEqualTo(3L * (RMI_TRANSPORT.connectTimeoutMillis() + 700));
        assertThat(other.recommendedMinClientTimeoutMillis()).isEqualTo(2_000 + other.worstCaseTransferMillis());
        assertThat(other.maxPayloadElements()).isEqualTo(500);
    }

    // ------------------------------------------------------------------ validation

    @Test
    @DisplayName("400: every field problem at once, with the exact messages; nothing started")
    void validation() {
        InvalidCallException missing = thrown(InvalidCallException.class,
                () -> module.run(new CollectiveCommand(null, null, -1L, null)));
        assertThat(missing.errors()).containsExactly(Map.entry("operation", "operation is required"),
                Map.entry("count", "count is required"), Map.entry("seed", "seed must be 0-9007199254740991, was -1"));

        assertThat(thrown(InvalidCallException.class,
                () -> module.run(command(CollectiveOperation.BROADCAST, 0))).errors())
                .containsExactly(Map.entry("count", "count must be 1-100000, was 0"));
        assertThat(thrown(InvalidCallException.class,
                () -> module.run(command(CollectiveOperation.GATHER, 100_001))).errors())
                .containsExactly(Map.entry("count", "count must be 1-100000, was 100001"));
        assertThat(thrown(InvalidCallException.class,
                () -> module.run(new CollectiveCommand(CollectiveOperation.BROADCAST, 5, 9_007_199_254_740_992L, null))).errors())
                .containsExactly(Map.entry("seed", "seed must be 0-9007199254740991, was 9007199254740992"));

        verify(rmi, never()).ensureStarted();
        assertThat(module.history()).isEmpty();
    }
}
