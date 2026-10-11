package com.udcf.modules.rmi;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventLogExporter;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.rmi.dto.CallCommand;
import com.udcf.modules.rmi.dto.ComparisonDto;
import com.udcf.modules.rmi.dto.FailureKindDto;
import com.udcf.modules.rmi.dto.HistoryEntryDto;
import com.udcf.modules.rmi.dto.HistoryKind;
import com.udcf.modules.rmi.dto.RmiCallDto;
import com.udcf.modules.rmi.dto.RmiNodeDto;
import com.udcf.modules.rmi.dto.RmiNodeState;
import com.udcf.modules.rmi.dto.RmiOverviewDto;
import com.udcf.web.NodeStateConflictException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.rmi.RemoteException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

/**
 * The Experiment 1 module on a standalone three-node cluster with real RMI on 127.0.0.1. No Spring
 * context, so crashes here never disturb other tests. No sleeps; Awaitility only waits for RMI's
 * accept threads to end.
 *
 * <p>Test-only ports 28771 to 28773 (RMI base 28770; Jai's sub-range 28750-28799).</p>
 */
@Timeout(120)
class RmiModuleTest {

    private static final int BASE = 28770;

    private ClusterEventBus bus;
    private Cluster cluster;
    private RmiModule module;

    @BeforeEach
    void setUp() {
        bus = RmiTestSupport.bus();
        cluster = RmiTestSupport.cluster(BASE, 3, bus);
        module = newModule(RmiTestSupport.PROPERTIES);
    }

    @AfterEach
    void tearDown() {
        module.close();
        cluster.close();
        bus.close();
    }

    private RmiModule newModule(RmiProperties properties) {
        return new RmiModule(cluster, bus, properties, RmiTestSupport.TRANSPORT, new SimpleMeterRegistry(),
                Clock.systemUTC());
    }

    private static CallCommand ping(int from, int to) {
        return new CallCommand(from, to, RmiMethod.PING, null, null);
    }

    private List<ClusterEvent> moduleEvents() {
        return bus.query(RmiModule.ID, null, 5000);
    }

    private List<ClusterEvent> moduleEvents(String type) {
        return moduleEvents().stream().filter(e -> e.type().equals(type)).toList();
    }

    private RmiNodeDto node(RmiOverviewDto overview, int nodeId) {
        return overview.nodes().stream().filter(n -> n.nodeId() == nodeId).findFirst().orElseThrow();
    }

    private void awaitAllReleased() {
        for (int k = 1; k <= 3; k++) {
            int port = BASE + k;
            await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> !RmiTestSupport.acceptThreadAlive(port) && RmiTestSupport.canBind(port));
        }
    }

    // ------------------------------------------------------------------ identity and reads

    @Test
    @DisplayName("id rmi, lab 1, title Client-Server via Java RMI, IDLE")
    void identity() {
        assertThat(module.id()).isEqualTo("rmi");
        assertThat(module.labNumber()).isEqualTo(1);
        assertThat(module.title()).isEqualTo("Client-Server via Java RMI");
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
    }

    @Test
    @DisplayName("overview() on a fresh module starts nothing and makes no call: every node NOT_STARTED, ports free")
    void overviewStartsNothing() {
        RmiOverviewDto overview = module.overview();

        assertThat(overview.nodes()).extracting(RmiNodeDto::state).containsOnly(RmiNodeState.NOT_STARTED);
        assertThat(overview.nodes()).extracting(RmiNodeDto::registryPort).containsExactly(28771, 28772, 28773);
        assertThat(overview.nodes()).extracting(RmiNodeDto::startFailure).containsOnlyNulls();
        assertThat(cluster.nodes()).allSatisfy(n -> assertThat(n.service(RmiNodeService.NAME)).isEmpty());
        assertThat(RmiTestSupport.canBind(28771)).isTrue();
        assertThat(overview.latest()).isNull();
        assertThat(overview.actionInProgress()).isNull();
        assertThat(overview.boundName()).isEqualTo("NodeEndpoint");
        assertThat(overview.failureKinds()).extracting(FailureKindDto::kind)
                .containsExactly(RemoteFailureKind.values());
        assertThat(overview.failureKinds()).allSatisfy(k -> assertThat(k.description()).isEqualTo(k.kind().description()));
        assertThat(moduleEvents()).isEmpty();
    }

    @Test
    @DisplayName("start exports on every up node, never on a crashed one, with ENDPOINT_EXPORTED at the core's own tick")
    void startSkipsCrashedNodes() {
        cluster.crash(2);

        RmiOverviewDto overview = module.start();

        assertThat(overview.status()).isEqualTo(ModuleStatus.RUNNING);
        assertThat(node(overview, 1).state()).isEqualTo(RmiNodeState.EXPORTED);
        assertThat(node(overview, 2).state()).isEqualTo(RmiNodeState.CRASHED);
        assertThat(node(overview, 3).state()).isEqualTo(RmiNodeState.EXPORTED);
        assertThat(cluster.node(2).service(RmiNodeService.NAME)).isEmpty();
        List<ClusterEvent> exported = moduleEvents("ENDPOINT_EXPORTED");
        assertThat(exported).extracting(ClusterEvent::nodeId).containsExactlyInAnyOrder(1, 3);
        for (ClusterEvent event : exported) {
            ClusterEvent started = bus.query("cluster", event.nodeId(), 100).stream()
                    .filter(e -> e.type().equals("SERVICE_STARTED")).findFirst().orElseThrow();
            assertThat(event.lamportTime()).as("no extra tick").isEqualTo(started.lamportTime());
            assertThat(event.data()).containsEntry("registryPort", BASE + event.nodeId())
                    .containsEntry("boundName", "NodeEndpoint");
        }
    }

    @Test
    @DisplayName("start is idempotent: the same services, no second ENDPOINT_EXPORTED")
    void startIsIdempotent() {
        module.start();
        RmiNodeService first = RmiNodeService.find(cluster.node(1)).orElseThrow();

        module.start();

        assertThat(RmiNodeService.find(cluster.node(1))).containsSame(first);
        assertThat(moduleEvents("ENDPOINT_EXPORTED")).hasSize(3);
    }

    @Test
    @DisplayName("state is read live: a crash shows CRASHED at once, a recovery shows EXPORTED again (the core re-exports)")
    void stateIsLive() {
        module.start();
        cluster.crash(3);
        assertThat(node(module.overview(), 3).state()).isEqualTo(RmiNodeState.CRASHED);
        cluster.recover(3);
        assertThat(node(module.overview(), 3).state()).isEqualTo(RmiNodeState.EXPORTED);
    }

    // ------------------------------------------------------------------ calls

    @Test
    @DisplayName("a call starts every up node lazily and uses the caller's own clock: send = before + 1, after = its clock")
    void callStartsLazilyAndUsesTheCallersClock() {
        assertThat(module.call(ping(2, 2)).outcome()).isEqualTo(CallOutcome.SUCCESS);
        assertThat(cluster.nodes()).allSatisfy(n -> assertThat(RmiNodeService.find(n)).isPresent());
        // Measured once the services run: starting one ticks its node's clock (the core's SERVICE_STARTED).
        long before = cluster.node(1).clock().current();
        long clusterClock = cluster.clusterClock().current();

        RmiCallDto call = module.call(ping(1, 3));

        assertThat(call.outcome()).isEqualTo(CallOutcome.SUCCESS);
        assertThat(call.senderLamportAtSend()).isEqualTo(before + 1);
        assertThat(call.replyLamport()).isGreaterThan(call.senderLamportAtSend());
        assertThat(call.senderLamportAfterReply()).isEqualTo(call.replyLamport() + 1)
                .isEqualTo(cluster.node(1).clock().current());
        assertThat(cluster.node(3).clock().current()).isEqualTo(call.replyLamport());
        assertThat(cluster.clusterClock().current()).as("no client clock").isEqualTo(clusterClock);
    }

    @Test
    @DisplayName("a crashed caller is refused (409): nothing recorded, nothing started, its clock unchanged")
    void crashedCallerIsRefused() {
        cluster.crash(1);
        long before = cluster.node(1).clock().current();

        assertThatThrownBy(() -> module.call(ping(1, 2))).isInstanceOf(NodeDownException.class);
        assertThatThrownBy(() -> module.compare(ping(1, 2))).isInstanceOf(NodeDownException.class);

        assertThat(module.history()).isEmpty();
        assertThat(cluster.node(2).service(RmiNodeService.NAME)).isEmpty();
        assertThat(cluster.node(1).clock().current()).isEqualTo(before);
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
    }

    @Test
    @DisplayName("a crashed target is a measured remote failure, returned as data, with CALL_FAILED and no CALL_SERVED")
    void crashedTargetIsData() {
        module.start();
        cluster.crash(2);

        RmiCallDto call = module.call(ping(1, 2));

        assertThat(call.outcome()).isEqualTo(CallOutcome.REMOTE_FAILURE);
        assertThat(call.failureKind()).isIn(RmiTestSupport.NODE_GONE);
        assertThat(call.roundTripNanos()).isNull();
        assertThat(call.timeToFailureNanos()).isNotNull();
        assertThat(moduleEvents("CALL_FAILED")).singleElement().satisfies(e -> {
            assertThat(e.nodeId()).isEqualTo(1);
            assertThat(e.peerId()).isEqualTo(2);
            assertThat(e.data()).containsEntry("kind", call.failureKind().name()).containsEntry("callId", 1L);
        });
        assertThat(moduleEvents("CALL_SERVED")).isEmpty();
        assertThatThrownBy(() -> module.compare(ping(1, 2))).isInstanceOf(NodeDownException.class)
                .extracting(e -> ((NodeDownException) e).nodeId()).isEqualTo(2);
    }

    @Test
    @DisplayName("a call and a comparison from a node to itself: local half on its own object, remote half through its own registry")
    void selfCallsAreAllowed() {
        RmiCallDto call = module.call(ping(2, 2));
        assertThat(call.outcome()).isEqualTo(CallOutcome.SUCCESS);
        assertThat(call.fromNode()).isEqualTo(2);
        assertThat(call.targetNode()).isEqualTo(2);

        ComparisonDto compared = module.compare(new CallCommand(2, 2, RmiMethod.COMPUTE_HASH, null, 50));
        assertThat(compared.local().stubClassName()).isEqualTo(NodeEndpointLogic.class.getName());
        assertThat(compared.remote().stubClassName()).contains("$Proxy");
        assertThat(compared.digestsMatch()).isTrue();

        List<ClusterEvent> events = moduleEvents();   // causal order (lamportTime, nodeId, sequence)
        List<String> callOne = events.stream().filter(e -> Long.valueOf(1).equals(e.data().get("callId")))
                .map(ClusterEvent::type).toList();
        assertThat(callOne).containsExactly("CALL_SERVED", "CALL_SUCCEEDED");
        assertThat(events).filteredOn(e -> Long.valueOf(2).equals(e.data().get("callId")))
                .extracting(ClusterEvent::type)
                .containsExactly("CALL_SERVED", "CALL_SUCCEEDED", "CALL_SERVED", "CALL_SUCCEEDED", "COMPARISON_FINISHED");
    }

    @Test
    @DisplayName("every problem with the method and its arguments is reported at once, before the guard or any node check")
    void invalidCallsListEveryField() {
        InvalidCallException e = (InvalidCallException) catchThrowable(
                () -> module.call(new CallCommand(9, 9, RmiMethod.PING, "x", 3)));
        assertThat(e.errors()).containsOnlyKeys("text", "rounds");
        assertThat(e.errors().get("text")).isEqualTo("text must be left out for PING");

        assertThat(((InvalidCallException) catchThrowable(() -> module.call(
                new CallCommand(1, 2, RmiMethod.COMPUTE_HASH, null, 1_000_001)))).errors())
                .containsEntry("rounds", "rounds must be 1-1000000, was 1000001");
        assertThat(((InvalidCallException) catchThrowable(() -> module.compare(
                new CallCommand(null, null, null, null, null)))).errors())
                .containsOnlyKeys("fromNode", "targetNode", "method");
        assertThat(module.toCall(new CallCommand(1, 2, RmiMethod.ECHO, "", null))).isEqualTo(RmiCall.echo(""));
    }

    @Test
    @DisplayName("BUSY while an action holds the guard: call, comparison and start get 409 naming the action")
    void busyRefusesEveryAction() {
        try (ModuleActionGuard.ActionTicket ignored = module.guard().begin("PING from node 1 to node 2")) {
            assertThat(module.status()).isEqualTo(ModuleStatus.BUSY);
            assertThat(module.overview().actionInProgress()).isEqualTo("PING from node 1 to node 2");
            assertThatThrownBy(() -> module.call(ping(1, 2))).isInstanceOf(ModuleBusyException.class)
                    .extracting(e -> ((ModuleBusyException) e).actionInProgress()).isEqualTo("PING from node 1 to node 2");
            assertThatThrownBy(() -> module.compare(ping(1, 2))).isInstanceOf(ModuleBusyException.class);
            assertThatThrownBy(module::start).isInstanceOf(ModuleBusyException.class);
        }
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
    }

    // ------------------------------------------------------------------ history

    @Test
    @DisplayName("the history is bounded by history-size, newest first; calls and comparisons share one id sequence")
    void historyIsBoundedAndShared() {
        module.close();
        module = newModule(new RmiProperties(1_000_000, 1024, 3, 3000, 300));
        assertThatThrownBy(module::latest).isInstanceOf(UnknownCallException.class);

        for (int i = 0; i < 4; i++) {
            module.call(ping(1, 2));
        }
        ComparisonDto compared = module.compare(ping(1, 3));

        assertThat(compared.id()).isEqualTo(5);
        assertThat(module.history()).extracting(HistoryEntryDto::id).containsExactly(5L, 4L, 3L);
        assertThat(module.history()).extracting(HistoryEntryDto::kind)
                .containsExactly(HistoryKind.COMPARISON, HistoryKind.CALL, HistoryKind.CALL);
        assertThat(module.latest().id()).isEqualTo(5);
        assertThat(module.entry(3).call().id()).isEqualTo(3);
        assertThatThrownBy(() -> module.entry(1)).isInstanceOf(UnknownCallException.class);
        assertThat(module.overview().latest().id()).isEqualTo(5);
    }

    // ------------------------------------------------------------------ reset and shutdown

    @Test
    @DisplayName("reset forgets the history (ids restart at 1), stops every service, frees the ports, publishes nothing")
    void resetIsACleanSlate() {
        module.call(ping(1, 2));
        int eventsBefore = moduleEvents().size();

        module.reset();

        assertThat(module.history()).isEmpty();
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(module.overview().nodes()).extracting(RmiNodeDto::state).containsOnly(RmiNodeState.NOT_STARTED);
        awaitAllReleased();
        assertThat(moduleEvents()).hasSize(eventsBefore);

        assertThat(module.call(ping(1, 2)).id()).isEqualTo(1);
    }

    @Test
    @DisplayName("close() leaves no exported object: lookups are refused, ports bind, no RMI accept thread within 5 s")
    void closeLeavesNothing() {
        module.start();
        assertThat(RmiTestSupport.acceptThreadAlive(28771)).isTrue();

        module.close();

        awaitAllReleased();
        assertThat(cluster.nodes()).allSatisfy(n ->
                assertThat(RmiNodeService.find(n).map(RmiNodeService::isRunning)).contains(false));
        Throwable refused = catchThrowable(() -> RmiTestSupport.remote(cluster).lookup(1));
        assertThat(refused).isInstanceOf(RemoteException.class);
        assertThat(RemoteFailureClassifier.classify(refused, CallStage.LOOKUP)).isIn(RmiTestSupport.NODE_GONE);
    }

    @Test
    @DisplayName("a node whose RMI cannot start keeps the reason in the overview; a comparison against it is a state conflict")
    void startFailureIsReported() throws Exception {
        try (ServerSocket blocker = new ServerSocket()) {
            blocker.bind(new InetSocketAddress(LoopbackSocketFactory.LOOPBACK, 28773));

            RmiOverviewDto overview = module.start();

            assertThat(node(overview, 3).state()).isEqualTo(RmiNodeState.NOT_STARTED);
            assertThat(node(overview, 3).startFailure()).contains("28773");
            assertThat(node(overview, 1).startFailure()).isNull();
            assertThatThrownBy(() -> module.compare(ping(1, 3))).isInstanceOf(NodeStateConflictException.class);
        }
        RmiOverviewDto again = module.start();
        assertThat(node(again, 3).state()).isEqualTo(RmiNodeState.EXPORTED);
        assertThat(node(again, 3).startFailure()).isNull();
    }

    // ------------------------------------------------------------------ events

    @Test
    @DisplayName("events: module rmi, the call's own Lamport times (no extra tick), single-line messages, the right peers")
    void eventsUseTheCallsLamportTimes() {
        RmiCallDto call = module.call(new CallCommand(1, 2, RmiMethod.ECHO, "line one\nline two | pipe", null));

        ClusterEvent served = moduleEvents("CALL_SERVED").get(0);
        ClusterEvent succeeded = moduleEvents("CALL_SUCCEEDED").get(0);
        assertThat(served.nodeId()).isEqualTo(2);
        assertThat(served.peerId()).isEqualTo(1);
        assertThat(served.lamportTime()).isEqualTo(call.replyLamport());
        assertThat(succeeded.nodeId()).isEqualTo(1);
        assertThat(succeeded.peerId()).isEqualTo(2);
        assertThat(succeeded.lamportTime()).isEqualTo(call.senderLamportAfterReply())
                .isEqualTo(cluster.node(1).clock().current());
        assertThat(succeeded.data()).containsEntry("roundTripNanos", call.roundTripNanos())
                .containsEntry("latencyMillis", RmiModule.wholeMillis(call.roundTripNanos()));
        assertThat(moduleEvents()).allSatisfy(e -> {
            assertThat(e.module()).isEqualTo("rmi");
            assertThat(DisplayText.isSingleLine(e.message())).as(e.message()).isTrue();
        });
    }

    @Test
    @DisplayName("latencyMillis is whole ms rounded half up (0 = under half a ms), REMOTE only; the exporter writes it")
    void latencyForTheEventExport() {
        module.publishCall(1, RmiResultFixtures.remoteSuccess(RmiCall.ping(), 400_000));
        module.publishCall(2, RmiResultFixtures.remoteSuccess(RmiCall.ping(), 2_600_000));
        module.publishCall(3, RmiResultFixtures.localSuccess(RmiCall.ping()));

        Map<Long, ClusterEvent> byCall = new java.util.HashMap<>();
        moduleEvents("CALL_SUCCEEDED").forEach(e -> byCall.put((Long) e.data().get("callId"), e));
        assertThat(byCall.get(1L).data()).containsEntry("latencyMillis", 0L).containsEntry("roundTripNanos", 400_000L);
        assertThat(byCall.get(2L).data()).containsEntry("latencyMillis", 3L).containsEntry("roundTripNanos", 2_600_000L);
        assertThat(byCall.get(3L).data()).doesNotContainKey("latencyMillis");
        assertThat(EventLogExporter.format(byCall.get(1L))).contains(" | latency=0.0 | ");
        assertThat(EventLogExporter.format(byCall.get(2L))).contains(" | latency=3.0 | ");
        assertThat(EventLogExporter.format(byCall.get(3L))).doesNotContain("latency=");
        assertThat(moduleEvents("CALL_SERVED")).allSatisfy(e -> assertThat(EventLogExporter.format(e))
                .doesNotContain("latency="));
    }

    @Test
    @DisplayName("whole milliseconds round half up: 499999 ns is 0, 500000 ns is 1, 2499999 ns is 2, 2500000 ns is 3")
    void wholeMillisRounding() {
        assertThat(Arrays.asList(RmiModule.wholeMillis(0), RmiModule.wholeMillis(499_999), RmiModule.wholeMillis(500_000),
                RmiModule.wholeMillis(2_499_999), RmiModule.wholeMillis(2_500_000))).containsExactly(0L, 0L, 1L, 2L, 3L);
        assertThatThrownBy(() -> RmiModule.wholeMillis(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a lookup failure's CALL_FAILED sits at the caller's current time; a call-stage failure at the time it sent")
    void failureEventLamportTimes() {
        cluster.node(1).clock().update(40);
        module.publishCall(1, RmiResultFixtures.lookupRefused(RmiCall.ping()));
        module.publishCall(2, RmiResultFixtures.callTimedOut(RmiCall.ping()));

        List<ClusterEvent> failed = moduleEvents("CALL_FAILED");
        assertThat(failed).filteredOn(e -> e.data().get("callId").equals(1L)).singleElement()
                .satisfies(e -> assertThat(e.lamportTime()).isEqualTo(41));
        assertThat(failed).filteredOn(e -> e.data().get("callId").equals(2L)).singleElement()
                .satisfies(e -> {
                    assertThat(e.lamportTime()).isEqualTo(6);
                    assertThat(e.data()).containsEntry("stage", "CALL").containsEntry("kind", "TIMED_OUT")
                            .doesNotContainKey("latencyMillis");
                });
        assertThat(cluster.node(1).clock().current()).as("publishing ticks nothing").isEqualTo(41);
    }
}
