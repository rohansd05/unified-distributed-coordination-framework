package com.udcf.modules.rmi;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.core.module.ExperimentModule;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.rmi.dto.CallCommand;
import com.udcf.modules.rmi.dto.CoHostingDto;
import com.udcf.modules.rmi.dto.ComparisonDto;
import com.udcf.modules.rmi.dto.FailureKindDto;
import com.udcf.modules.rmi.dto.HistoryEntryDto;
import com.udcf.modules.rmi.dto.MethodDto;
import com.udcf.modules.rmi.dto.ParameterDto;
import com.udcf.modules.rmi.dto.RegistryDto;
import com.udcf.modules.rmi.dto.RmiCallDto;
import com.udcf.modules.rmi.dto.RmiLimitsDto;
import com.udcf.modules.rmi.dto.RmiNodeDto;
import com.udcf.modules.rmi.dto.RmiNodeState;
import com.udcf.modules.rmi.dto.RmiOverviewDto;
import com.udcf.web.NodeStateConflictException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Experiment 1 (lab 1) on the shared cluster: Java RMI calls between real nodes, each through the
 * target node's own registry on {@code ports().rmi()} (110k) and a stub, the same call made
 * directly for comparison, and each node's registry listed over the network.
 *
 * <p><b>Services.</b> This module owns the lifecycle of the per-node {@link RmiNodeService}s. They
 * start lazily: on {@code POST /start} or before the first call, on every node that is up, never
 * on a crashed node ({@link #ensureStarted()}). Crash and recovery reach them through the node
 * ({@code ClusterNode.crash/recover}); the module always reads live state from the services,
 * never a cached copy (R10). {@link #reset()} and {@link #close()} stop them.</p>
 *
 * <p><b>Calls.</b> The caller is a real node: every call is made by an {@link RmiInvoker} built
 * with the calling node's id and its own shared Lamport clock; there is no client clock. A crashed
 * caller is refused (409); a crashed target is a measured remote failure, returned as data (200).
 * Calls, comparisons and starts each hold the module's {@link ModuleActionGuard} for their whole
 * duration (one at a time; a second gets 409), and run on the request thread. Reads (overview,
 * registries, history) never take the guard and never start a service.</p>
 *
 * <p><b>Events</b> (module {@value #ID}) never tick a clock: each carries the Lamport time of the
 * step it reports, so link L4 shows exactly the ticks of the call itself.</p>
 * <ul>
 *   <li>{@code ENDPOINT_EXPORTED} on the node the module started, at its clock's current value
 *       (the core's SERVICE_STARTED or SERVICE_RECOVERED already ticked it);</li>
 *   <li>{@code CALL_SERVED} on the target, peer the caller, at the reply's Lamport time;</li>
 *   <li>{@code CALL_SUCCEEDED} on the caller, peer the target, at its Lamport time after the
 *       receive rule;</li>
 *   <li>{@code CALL_FAILED} on the caller, peer the target, at the Lamport time it sent with the
 *       call (stage CALL) or at its clock's current value (stage LOOKUP: nothing was sent with a
 *       Lamport time);</li>
 *   <li>{@code COMPARISON_FINISHED} on the caller, peer the target, at its clock's current value
 *       after both halves.</li>
 * </ul>
 *
 * <p><b>Status.</b> BUSY while an action holds the guard; otherwise RUNNING while any node's
 * endpoint is exported; otherwise IDLE. A remote failure is data, never ERROR.</p>
 *
 * <p><b>Honesty (R7).</b> Every time, size and Lamport value comes from the call itself; what was
 * not measured is null. The one simulated aspect, that the nodes share one process, is labelled
 * ({@link #CO_HOSTING}).</p>
 */
@Component
public class RmiModule implements ExperimentModule, AutoCloseable {

    public static final String ID = "rmi";
    static final String TITLE = "Client-Server via Java RMI";

    static final CoHostingDto CO_HOSTING = new CoHostingDto(true, "All nodes run inside one backend process on "
            + "one computer, so they are separate nodes sharing a machine, not separate machines. Every call on "
            + "this page still goes through the target node's own RMI registry and a stub over a real loopback "
            + "socket, so the lookup, the marshalling and the round trip are real and measured; only the network "
            + "distance between the nodes is missing.");

    static final String COMPARISON_NOTE = "Each side is a single measurement: the direct call runs first, then "
            + "the same call through RMI, with no warm-up call and no averaging. The first call after the backend "
            + "starts also pays for loading classes, so the direct side can be the slower one: the overhead can be "
            + "negative and the ratio below 1. The figures are shown exactly as measured.";

    /** What {@code lookupNanos} measures; verified in the JDK 21 source and by RmiLookupBasisTest. */
    static final String LOOKUP_BASIS = "The time of a successful registry lookup on the calling node: one request "
            + "to the target node's registry, on a new or a pooled loopback connection, plus RMI's distributed "
            + "garbage collection \"dirty\" call to the target node, which RMI makes on the same thread before "
            + "lookup() returns whenever this process holds no other live stub for that exported endpoint (always "
            + "after the node exported it anew). A failed dirty call does not fail the lookup. The method call "
            + "itself is not included; it is roundTripNanos.";

    static final List<FailureKindDto> FAILURE_KINDS = Arrays.stream(RemoteFailureKind.values())
            .map(kind -> new FailureKindDto(kind, kind.description()))
            .toList();

    private static final Logger log = LoggerFactory.getLogger(RmiModule.class);

    private final Cluster cluster;
    private final ClusterEventBus bus;
    private final RmiProperties properties;
    private final RmiTransportProperties transport;
    private final Clock clock;
    private final RemoteEndpointLookup remote;
    private final LocalEndpointLookup local;
    private final CallTimer timer = new CallTimer(System::nanoTime);
    private final RmiHistory history;
    private final RmiMetrics metrics;
    private final List<MethodDto> methods;
    private final ModuleActionGuard guard = new ModuleActionGuard(ID);
    private final Map<Integer, String> startFailures = new ConcurrentHashMap<>();

    public RmiModule(Cluster cluster, ClusterEventBus bus, RmiProperties properties, RmiTransportProperties transport,
                     MeterRegistry meterRegistry, Clock clock) {
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.remote = RemoteEndpointLookup.of(cluster, properties, transport);
        this.local = new LocalEndpointLookup(cluster);
        this.history = new RmiHistory(properties.historySize());
        this.metrics = new RmiMetrics(meterRegistry, cluster);
        this.methods = methods(properties);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int labNumber() {
        return 1;
    }

    @Override
    public String title() {
        return TITLE;
    }

    @Override
    public ModuleStatus status() {
        if (guard.isBusy()) {
            return ModuleStatus.BUSY;
        }
        return cluster.nodes().stream().anyMatch(RmiModule::running) ? ModuleStatus.RUNNING : ModuleStatus.IDLE;
    }

    /**
     * Clean slate: forgets every call and comparison (ids restart at 1; a call still finishing
     * cannot record itself) and every start failure, then stops every node's RMI service. Never
     * throws, never takes the guard and publishes nothing; the cluster reset clears the event
     * history. After it, the core still lists each stopped service, so the next start is logged as
     * SERVICE_RECOVERED, not SERVICE_STARTED.
     */
    @Override
    public void reset() {
        history.clear();
        startFailures.clear();
        List<String> failures = stopAll();
        if (!failures.isEmpty()) {
            log.warn("RMI reset: {}", String.join("; ", failures));
        }
    }

    /**
     * Stops every node's RMI service, so no exported object, listener or accept thread outlives the
     * application (RMI's reaper thread would otherwise keep the JVM alive). Called by Spring on
     * context shutdown, before the cluster closes.
     *
     * @throws IllegalStateException after trying every node, if any service failed to stop
     */
    @Override
    public void close() {
        List<String> failures = stopAll();
        if (!failures.isEmpty()) {
            throw new IllegalStateException("RMI services did not stop cleanly: " + String.join("; ", failures));
        }
    }

    private List<String> stopAll() {
        List<String> failures = new ArrayList<>();
        for (ClusterNode node : cluster.nodes()) {
            Optional<RmiNodeService> service = RmiNodeService.find(node);
            if (service.isEmpty()) {
                continue;
            }
            try {
                service.get().stop();
            } catch (RuntimeException e) {
                failures.add("node " + node.id() + ": " + e.getMessage());
            }
        }
        return failures;
    }

    // ------------------------------------------------------------------ link L6 (Experiments 9 and 10)

    /** The one REMOTE lookup this module uses (one client channel per node); for Experiment 9. */
    public RemoteEndpointLookup remoteLookup() {
        return remote;
    }

    /** The LOCAL lookup (a node's exported endpoint object itself); for Experiment 9. */
    public LocalEndpointLookup localLookup() {
        return local;
    }

    /**
     * Starts the RMI service on every node that is up, never on a crashed node. Idempotent. A node
     * whose start fails keeps the failure as {@code startFailure} in the overview, and a call to it
     * then fails as a measured refusal. Does not take the guard (Experiment 9 may call it).
     */
    public void ensureStarted() {
        for (ClusterNode node : cluster.nodes()) {
            if (!node.isUp()) {
                continue;
            }
            boolean wasRunning = running(node);
            try {
                RmiNodeService service = RmiNodeService.on(node, properties, transport);
                startFailures.remove(node.id());
                if (!wasRunning && service.isRunning()) {
                    publishExported(node, service);
                }
            } catch (NodeDownException e) {
                // crashed since the check: nothing to start
            } catch (IllegalStateException e) {
                Throwable reason = e.getCause() != null ? e.getCause() : e;
                String message = DisplayText.singleLine(String.valueOf(reason.getMessage()),
                        properties.maxFailureMessageLength());
                startFailures.put(node.id(), message);
                log.warn("RMI could not start on node {}: {}", node.id(), message);
            }
        }
    }

    // ------------------------------------------------------------------ reads (no guard, never start)

    /** Everything the page needs to offer a call. No remote call; never starts a service. */
    public RmiOverviewDto overview() {
        List<RmiNodeDto> nodes = cluster.nodes().stream()
                .map(node -> new RmiNodeDto(node.id(), node.status(), stateOf(node), node.ports().rmi(),
                        startFailures.get(node.id())))
                .toList();
        RmiLimitsDto limits = new RmiLimitsDto(properties.maxHashRounds(), properties.maxEchoLength(),
                properties.historySize(), properties.callTimeoutMillis(), transport.connectTimeoutMillis(),
                properties.maxFailureMessageLength());
        return new RmiOverviewDto(status(), guard.currentAction().orElse(null), NodeEndpoint.BOUND_NAME, nodes,
                methods, limits, PayloadSizer.LABEL, LOOKUP_BASIS, CO_HOSTING, COMPARISON_NOTE, FAILURE_KINDS,
                history.latest().orElse(null));
    }

    /** Every node's registry, listed over the network. Never starts a service. */
    public List<RegistryDto> registries() {
        return cluster.nodes().stream().map(this::registryOf).toList();
    }

    /**
     * One node's registry, listed over the network.
     *
     * @throws com.udcf.core.cluster.UnknownNodeException no such node (404)
     */
    public RegistryDto registry(int nodeId) {
        return registryOf(cluster.node(nodeId));
    }

    /** The kept calls and comparisons, newest first. */
    public List<HistoryEntryDto> history() {
        return history.all();
    }

    /** @throws UnknownCallException (with a null id) if nothing is kept */
    public HistoryEntryDto latest() {
        return history.latest().orElseThrow(() -> new UnknownCallException(null));
    }

    /** @throws UnknownCallException if no kept entry has this id */
    public HistoryEntryDto entry(long id) {
        return history.find(id).orElseThrow(() -> new UnknownCallException(id));
    }

    // ------------------------------------------------------------------ actions (guarded)

    /**
     * Starts the RMI service on every node that is up. Idempotent.
     *
     * @throws com.udcf.core.module.ModuleBusyException another action is running (409)
     */
    public RmiOverviewDto start() {
        try (ModuleActionGuard.ActionTicket ignored = guard.begin("Start RMI on every live node")) {
            ensureStarted();
        }
        return overview();
    }

    /**
     * One call from {@code fromNode} to {@code targetNode} through the target's registry and a
     * stub. A remote failure is returned as data.
     *
     * @throws InvalidCallException  method and arguments do not fit, or a limit is broken (400)
     * @throws com.udcf.core.cluster.UnknownNodeException no such node (404)
     * @throws com.udcf.core.module.ModuleBusyException  another action is running (409)
     * @throws NodeDownException     the calling node is crashed (409)
     */
    public RmiCallDto call(CallCommand command) {
        RmiCall call = toCall(command);
        ClusterNode from = cluster.node(command.fromNode());
        ClusterNode target = cluster.node(command.targetNode());
        try (ModuleActionGuard.ActionTicket ignored = guard.begin(call.method() + " from node " + from.id()
                + " to node " + target.id())) {
            requireUp(from);
            ensureStarted();
            Instant calledAt = clock.instant();
            RmiCallResult result = invoker(from).invoke(remote, target.id(), call);
            RmiHistory.Slot slot = history.reserve();
            RmiCallDto dto = RmiDtoMapper.call(slot.id(), calledAt, result);
            history.record(slot, HistoryEntryDto.of(dto));
            metrics.record(result);
            publishCall(slot.id(), result);
            return dto;
        }
    }

    /**
     * The same call made directly on the target's endpoint object (LOCAL), then through its
     * registry and a stub (REMOTE), as {@link RmiInvoker#compare} does, with both results kept.
     *
     * @throws InvalidCallException        (400)
     * @throws com.udcf.core.cluster.UnknownNodeException (404)
     * @throws com.udcf.core.module.ModuleBusyException (409)
     * @throws NodeDownException           the calling node or the target is crashed (409): a dead
     *                                     target computes nothing, so the local half cannot run
     * @throws NodeStateConflictException  the target is up but RMI could not start on it (409)
     */
    public ComparisonDto compare(CallCommand command) {
        RmiCall call = toCall(command);
        ClusterNode from = cluster.node(command.fromNode());
        ClusterNode target = cluster.node(command.targetNode());
        try (ModuleActionGuard.ActionTicket ignored = guard.begin("Comparison of " + call.method() + " from node "
                + from.id() + " on node " + target.id())) {
            requireUp(from);
            requireUp(target);
            ensureStarted();
            if (!running(target)) {
                String failure = startFailures.get(target.id());
                throw new NodeStateConflictException(target.id(), "RMI is not running on node " + target.id()
                        + (failure == null ? "" : ": " + failure));
            }
            RmiInvoker invoker = invoker(from);
            Instant calledAt = clock.instant();
            RmiCallResult localResult;
            try {
                localResult = invoker.invoke(local, target.id(), call);
            } catch (IllegalStateException e) {
                if (!target.isUp()) {
                    throw new NodeDownException(target.id());   // crashed between the check and the call
                }
                throw e;
            }
            RmiCallResult remoteResult = invoker.invoke(remote, target.id(), call);
            RmiHistory.Slot slot = history.reserve();
            ComparisonDto dto = RmiDtoMapper.comparison(slot.id(), calledAt, localResult, remoteResult,
                    COMPARISON_NOTE);
            history.record(slot, HistoryEntryDto.of(dto));
            metrics.record(localResult);
            metrics.record(remoteResult);
            publishCall(slot.id(), localResult);
            publishCall(slot.id(), remoteResult);
            publishComparison(slot.id(), from, dto);
            return dto;
        }
    }

    // ------------------------------------------------------------------ validation

    /** Checks the method's arguments; every problem is reported at once (400). */
    RmiCall toCall(CallCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Map<String, String> errors = new LinkedHashMap<>();
        if (command.fromNode() == null) {
            errors.put("fromNode", "fromNode is required");
        }
        if (command.targetNode() == null) {
            errors.put("targetNode", "targetNode is required");
        }
        RmiMethod method = command.method();
        if (method == null) {
            errors.put("method", "method is required");
        } else {
            String text = command.text();
            if (method == RmiMethod.ECHO) {
                if (text == null) {
                    errors.put("text", "text is required for ECHO");
                } else if (text.length() > properties.maxEchoLength()) {
                    errors.put("text", "text must be at most " + properties.maxEchoLength() + " characters, was "
                            + text.length());
                }
            } else if (text != null) {
                errors.put("text", "text must be left out for " + method);
            }
            Integer rounds = command.rounds();
            if (method == RmiMethod.COMPUTE_HASH) {
                if (rounds == null) {
                    errors.put("rounds", "rounds is required for COMPUTE_HASH");
                } else if (rounds < 1 || rounds > properties.maxHashRounds()) {
                    errors.put("rounds", "rounds must be 1-" + properties.maxHashRounds() + ", was " + rounds);
                }
            } else if (rounds != null) {
                errors.put("rounds", "rounds must be left out for " + method);
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidCallException(errors);
        }
        RmiCall call = new RmiCall(method, command.text(), command.rounds());
        call.validate(properties);
        return call;
    }

    // ------------------------------------------------------------------ events

    /** The call's events; see the class description for their Lamport times. */
    void publishCall(long callId, RmiCallResult result) {
        int caller = result.senderNodeId();
        int target = result.targetNodeId();
        String how = result.path() == CallPath.REMOTE ? "over RMI" : "directly (local call)";
        String what = result.method() + " (" + DisplayText.cut(result.argumentSummary(), 80) + ")";
        if (result.succeeded()) {
            Map<String, Object> served = callData(callId, result);
            served.put("senderLamportAtSend", result.senderLamportAtSend());
            bus.publish(EventDraft.of(ID, target, "CALL_SERVED", result.replyLamport())
                    .withPeer(caller)
                    .withMessage("Node " + target + " answered " + what + " from node " + caller + " " + how)
                    .withData(served));
            bus.publish(EventDraft.of(ID, caller, "CALL_SUCCEEDED", result.senderLamportAfterReply())
                    .withPeer(target)
                    .withMessage("Node " + caller + " called " + what + " on node " + target + " " + how
                            + " and got the reply in " + millis(result.roundTripNanos()))
                    .withData(successData(callId, result)));
            return;
        }
        boolean sent = result.failureStage() == CallStage.CALL;
        long lamport = sent ? result.senderLamportAtSend() : cluster.node(caller).clock().current();
        Map<String, Object> data = callData(callId, result);
        data.put("stage", result.failureStage().name());
        data.put("kind", result.failureKind().name());
        data.put("failureClass", result.failureClass());
        putIfPresent(data, "failureCauseClass", result.failureCauseClass());
        data.put("timeToFailureNanos", result.timeToFailureNanos());
        putIfPresent(data, "senderLamportAtSend", result.senderLamportAtSend());
        bus.publish(EventDraft.of(ID, caller, "CALL_FAILED", lamport)
                .withPeer(target)
                .withMessage("Node " + caller + " called " + what + " on node " + target + " " + how + "; it failed "
                        + (sent ? "during the call" : "at the lookup") + " after "
                        + millis(result.timeToFailureNanos()) + ": " + result.failureKind() + " ("
                        + result.failureClass() + ")")
                .withData(data));
    }

    /**
     * The data of {@code CALL_SUCCEEDED}. {@code latencyMillis} (what the event export writes as
     * {@code latency=}, link L5) is set for REMOTE calls only: the measured round trip in whole
     * milliseconds, rounded half up, so 0 means under half a millisecond; the exact value stays in
     * {@code roundTripNanos}.
     */
    static Map<String, Object> successData(long callId, RmiCallResult result) {
        Map<String, Object> data = callData(callId, result);
        data.put("lookupNanos", result.lookupNanos());
        data.put("roundTripNanos", result.roundTripNanos());
        putIfPresent(data, "argumentBytes", result.argumentBytes());
        putIfPresent(data, "resultBytes", result.resultBytes());
        data.put("senderLamportAtSend", result.senderLamportAtSend());
        data.put("replyLamport", result.replyLamport());
        if (result.path() == CallPath.REMOTE) {
            data.put("latencyMillis", wholeMillis(result.roundTripNanos()));
        }
        return data;
    }

    /** Nanoseconds to whole milliseconds, rounded half up (499999 ns is 0 ms, 500000 ns is 1 ms). */
    static long wholeMillis(long nanos) {
        if (nanos < 0) {
            throw new IllegalArgumentException("nanos must be >= 0, was " + nanos);
        }
        return (nanos + 500_000L) / 1_000_000L;
    }

    private static Map<String, Object> callData(long callId, RmiCallResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("callId", callId);
        data.put("method", result.method().name());
        data.put("path", result.path().name());
        return data;
    }

    private void publishComparison(long callId, ClusterNode from, ComparisonDto dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("callId", callId);
        data.put("method", dto.method().name());
        putIfPresent(data, "localNanos", dto.localNanos());
        putIfPresent(data, "remoteNanos", dto.remoteNanos());
        putIfPresent(data, "overheadNanos", dto.overheadNanos());
        putIfPresent(data, "ratio", dto.ratio());
        putIfPresent(data, "digestsMatch", dto.digestsMatch());
        String remoteSide = dto.remoteNanos() == null ? "remote failed" : "remote " + millis(dto.remoteNanos());
        bus.publish(EventDraft.of(ID, from.id(), "COMPARISON_FINISHED", from.clock().current())
                .withPeer(dto.targetNode())
                .withMessage("Node " + from.id() + " compared " + dto.method() + " on node " + dto.targetNode()
                        + ": local " + millis(dto.localNanos()) + ", " + remoteSide)
                .withData(data));
    }

    private void publishExported(ClusterNode node, RmiNodeService service) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("registryPort", service.port());
        data.put("boundName", NodeEndpoint.BOUND_NAME);
        bus.publish(EventDraft.of(ID, node.id(), "ENDPOINT_EXPORTED", node.clock().current())
                .withMessage("Node " + node.id() + " exported " + NodeEndpoint.BOUND_NAME + " in its RMI registry on port "
                        + service.port())
                .withData(data));
    }

    private static void putIfPresent(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    private static String millis(Long nanos) {
        return nanos == null ? "unknown" : String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000d);
    }

    // ------------------------------------------------------------------ helpers

    private RmiInvoker invoker(ClusterNode from) {
        return new RmiInvoker(from.id(), from.clock(), properties, timer);
    }

    private RegistryDto registryOf(ClusterNode node) {
        RmiNodeState state = stateOf(node);
        try {
            return RmiDtoMapper.registry(remote.snapshot(node.id()), state);
        } catch (RemoteException | NotBoundException e) {
            return RmiDtoMapper.registryFailure(node.id(), node.ports().rmi(), state, e,
                    properties.maxFailureMessageLength());
        }
    }

    private static void requireUp(ClusterNode node) {
        if (!node.isUp()) {
            throw new NodeDownException(node.id());
        }
    }

    private static boolean running(ClusterNode node) {
        return RmiNodeService.find(node).map(RmiNodeService::isRunning).orElse(false);
    }

    private static RmiNodeState stateOf(ClusterNode node) {
        if (!node.isUp()) {
            return RmiNodeState.CRASHED;
        }
        return running(node) ? RmiNodeState.EXPORTED : RmiNodeState.NOT_STARTED;
    }

    private static List<MethodDto> methods(RmiProperties properties) {
        return List.of(
                new MethodDto(RmiMethod.PING, "Answers at once: proves the node is reachable.", List.of()),
                new MethodDto(RmiMethod.NODE_INFO, "Returns the node's id, registry port, capacity profile, uptime, "
                        + "Lamport time and Java version.", List.of()),
                new MethodDto(RmiMethod.ECHO, "Returns the text unchanged.", List.of(new ParameterDto("text", "string",
                        "The text to send; it may be empty. Length in UTF-16 characters.", 0,
                        properties.maxEchoLength()))),
                new MethodDto(RmiMethod.COMPUTE_HASH, "Chained SHA-256 over the node's seed, rounds times: real work "
                        + "done on the node.", List.of(new ParameterDto("rounds", "int",
                        "How many SHA-256 rounds the node computes.", 1, properties.maxHashRounds()))));
    }

    /** For tests: holding the guard makes every action answer 409. */
    ModuleActionGuard guard() {
        return guard;
    }
}
