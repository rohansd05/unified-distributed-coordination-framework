package com.udcf.modules.mpi;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.cluster.NodeRole;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.core.module.ExperimentModule;
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
import com.udcf.modules.rmi.InvalidCallException;
import com.udcf.modules.rmi.PayloadSizer;
import com.udcf.modules.rmi.RmiModule;
import com.udcf.modules.rmi.RmiProperties;
import com.udcf.modules.rmi.RmiTransportProperties;
import com.udcf.modules.rmi.dto.CoHostingDto;
import com.udcf.modules.rmi.dto.RmiNodeDto;
import com.udcf.web.NodeStateConflictException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Experiment 9 (lab 9) on the shared cluster: broadcast, scatter and gather from a root node to
 * every other live node, each transfer a real RMI call to the target node's
 * {@link CollectiveEndpoint} (link L6), run by E9a's {@link CollectiveExecutor}.
 *
 * <p><b>Participants and root.</b> Every cluster node takes part. A node crashed at the start is
 * skipped (listed, never hidden). A live node whose RMI is not running stays a rank and fails at
 * LOOKUP; a node that crashes during the run is a FAILED transfer. The root is the requested node,
 * otherwise the live leader, otherwise the lowest live node id ({@link MpiRoleSelector}).</p>
 *
 * <p><b>RMI.</b> Experiment 1 owns the services: every action first calls
 * {@link RmiModule#ensureStarted()} (which does not take the rmi guard), and reads never start
 * anything (R10).</p>
 *
 * <p><b>Concurrency.</b> Start and collective each hold this module's {@link ModuleActionGuard} for
 * their whole duration, on the request thread, released by try-with-resources (a second request gets
 * 409 and is never queued; it publishes {@code ACTION_REJECTED_BUSY}). Reads never take the guard.</p>
 *
 * <p><b>Events</b> (module {@value #ID}) never tick a clock; each carries the Lamport time of the
 * step it reports: {@code COLLECTIVE_ENDPOINT_EXPORTED} (the started node, its clock),
 * {@code COLLECTIVE_STARTED} (root, its clock before the first transfer), then
 * {@code COLLECTIVE_FINISHED} or {@code COLLECTIVE_REFUSED} (root, its clock afterwards), and
 * {@code ACTION_REJECTED_BUSY} (node 0, the cluster clock).</p>
 *
 * <p><b>Status.</b> BUSY while an action holds the guard; otherwise RUNNING while any node exports
 * its CollectiveEndpoint; otherwise IDLE.</p>
 *
 * <p><b>Honesty (R7).</b> Every time, size, checksum and Lamport value comes from the run; what was
 * not measured is null. The vectors are not kept. The co-hosting of the nodes is labelled
 * ({@link #CO_HOSTING}). {@code worstCaseTransferMillis} is a bound derived from configured timeouts,
 * not a measurement.</p>
 */
@Component
public class MpiModule implements ExperimentModule {

    public static final String ID = "mpi";
    static final String TITLE = "MPI Collectives";

    /** The largest seed a JavaScript number holds exactly (2^53 - 1), so the page shows the seed used. */
    public static final long MAX_SEED = (1L << 53) - 1;

    /** Exchanges in one transfer: the registry lookup, RMI's DGC dirty call inside it, the method call. */
    public static final int EXCHANGES_PER_TRANSFER = 3;

    static final CoHostingDto CO_HOSTING = new CoHostingDto(true, "All nodes run inside one backend process on one "
            + "computer, so they are separate nodes sharing a machine, not separate machines. Every transfer on this "
            + "page still goes from the root through the target node's own RMI registry and a stub over a real "
            + "loopback socket, so the lookup, the marshalling and the latency are real and measured; only the network "
            + "distance between the nodes is missing.");

    static final String ALGORITHM_NOTE = "Each collective is linear: the root handles the other ranks one after "
            + "another in rank order, and only the root sends or asks. Real MPI libraries use trees (for example a "
            + "binomial broadcast) so the root does not make every transfer itself; that is not built here. The root's "
            + "own part never crosses the network. A failed transfer is reported and the collective goes on with the "
            + "next rank; once the deadline has passed, no new transfer starts.";

    private final Cluster cluster;
    private final ClusterEventBus bus;
    private final MpiProperties properties;
    private final RmiModule rmi;
    private final RmiProperties rmiProperties;
    private final RmiTransportProperties rmiTransport;
    private final Clock clock;
    private final CollectiveTransport transport;
    private final CollectiveExecutor executor;
    private final MpiHistory history;
    private final MpiMetrics metrics;
    private final ModuleActionGuard guard = new ModuleActionGuard(ID);

    @Autowired
    public MpiModule(Cluster cluster, ClusterEventBus bus, MpiProperties properties, RmiModule rmi,
                     RmiProperties rmiProperties, RmiTransportProperties rmiTransport, MeterRegistry meterRegistry,
                     Clock clock) {
        this(cluster, bus, properties, rmi, rmiProperties, rmiTransport, meterRegistry, clock,
                RmiCollectiveTransport.of(rmi), System::nanoTime);
    }

    /** For tests: a scripted transport and time source. */
    MpiModule(Cluster cluster, ClusterEventBus bus, MpiProperties properties, RmiModule rmi, RmiProperties rmiProperties,
              RmiTransportProperties rmiTransport, MeterRegistry meterRegistry, Clock clock,
              CollectiveTransport transport, LongSupplier nanoClock) {
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.rmi = Objects.requireNonNull(rmi, "rmi must not be null");
        this.rmiProperties = Objects.requireNonNull(rmiProperties, "rmiProperties must not be null");
        this.rmiTransport = Objects.requireNonNull(rmiTransport, "rmiTransport must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.executor = new CollectiveExecutor(properties, nanoClock);
        this.history = new MpiHistory(properties.historySize());
        this.metrics = new MpiMetrics(meterRegistry);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int labNumber() {
        return 9;
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
        return exportedIds().isEmpty() ? ModuleStatus.IDLE : ModuleStatus.RUNNING;
    }

    /**
     * Clean slate: forgets every collective (ids restart at 1; one still running cannot record
     * itself). Never throws, never takes the guard, publishes nothing. Stopping RMI is Experiment 1's
     * reset.
     */
    @Override
    public void reset() {
        history.clear();
    }

    // ------------------------------------------------------------------ reads (no guard, never start)

    /** Everything the page needs to offer a collective. No remote call; never starts a service. */
    public MpiOverviewDto overview() {
        Map<Integer, RmiNodeDto> rmiNodes = rmi.overview().nodes().stream()
                .collect(Collectors.toMap(RmiNodeDto::nodeId, Function.identity()));
        Set<Integer> live = liveIds();
        Optional<MpiRoleSelector.Choice> root = MpiRoleSelector.select(null, cluster.leaderId(), live);
        List<MpiNodeDto> participants = cluster.nodes().stream()
                .map(node -> participant(node, rmiNodes.get(node.id())))
                .toList();
        return new MpiOverviewDto(status(), guard.currentAction().orElse(null), CollectiveEndpoint.BOUND_NAME,
                operations(live.size()), limits(), participants, root.map(MpiRoleSelector.Choice::nodeId).orElse(null),
                root.map(MpiRoleSelector.Choice::source).orElse(null), RmiCollectiveTransport.LATENCY_BASIS,
                PayloadSizer.LABEL, ALGORITHM_NOTE, CO_HOSTING, history.latest().orElse(null));
    }

    /** The kept collectives, newest first. */
    public List<CollectiveDto> history() {
        return history.all();
    }

    /** @throws UnknownCollectiveException (with a null id) if nothing is kept */
    public CollectiveDto latest() {
        return history.latest().orElseThrow(() -> new UnknownCollectiveException(null));
    }

    /** @throws UnknownCollectiveException if no kept collective has this id */
    public CollectiveDto collective(long id) {
        return history.find(id).orElseThrow(() -> new UnknownCollectiveException(id));
    }

    // ------------------------------------------------------------------ actions (guarded)

    /**
     * Starts RMI (with every node's CollectiveEndpoint) on every node that is up. Idempotent.
     *
     * @throws ModuleBusyException another action is running (409)
     */
    public MpiOverviewDto start() {
        try (ModuleActionGuard.ActionTicket ignored = begin("Start RMI on every live node for collectives")) {
            startRmi();
        }
        return overview();
    }

    /**
     * Runs one collective from its root to every other live node. COMPLETE, PARTIAL, FAILED and
     * REFUSED (too few elements for the live nodes) are all results (200).
     *
     * @throws InvalidCallException        a field is missing or out of range (400)
     * @throws com.udcf.core.cluster.UnknownNodeException the requested root does not exist (404)
     * @throws ModuleBusyException         another action is running (409)
     * @throws NodeDownException           the requested root is crashed (409)
     * @throws NodeStateConflictException  no root was requested and every node is crashed (409)
     */
    public CollectiveDto run(CollectiveCommand command) {
        validate(command);
        Integer requested = command.rootNode();
        if (requested != null) {
            cluster.node(requested);   // 404 for an unknown node, before taking the guard
        }
        try (ModuleActionGuard.ActionTicket ignored = begin(describe(command))) {
            if (requested != null && !cluster.node(requested).isUp()) {
                throw new NodeDownException(requested);   // a dead root cannot send
            }
            startRmi();
            Set<Integer> live = liveIds();
            MpiRoleSelector.Choice root = MpiRoleSelector.select(requested, cluster.leaderId(), live)
                    .orElseThrow(() -> new NodeStateConflictException(0,
                            "Every node is crashed, so no node can be the root of a collective"));
            return execute(command, root, live);
        }
    }

    private CollectiveDto execute(CollectiveCommand command, MpiRoleSelector.Choice root, Set<Integer> live) {
        MpiHistory.Slot slot = history.reserve();
        CollectiveResult result;
        CollectiveDto dto;
        try {
            long seed = command.seed() == null ? slot.id() : command.seed();
            ClusterNode rootNode = cluster.node(root.nodeId());
            List<Integer> participants = cluster.nodes().stream().map(ClusterNode::id).toList();
            CollectiveRequest request = new CollectiveRequest(slot.id(), command.operation(), root.nodeId(), participants,
                    live, command.count(), seed);
            Instant startedAt = clock.instant();
            publishStarted(request, root.source(), live, rootNode.clock().current());
            result = executor.execute(request, rootNode.clock(), transport);
            dto = MpiDtoMapper.collective(startedAt, root.source(), result);
        } catch (RuntimeException unexpected) {
            history.cancel(slot);   // no id stays reserved without an entry
            throw unexpected;
        }
        history.record(slot, dto);
        metrics.record(result);
        publishOutcome(dto);
        return dto;
    }

    // ------------------------------------------------------------------ validation

    /** Checks every field; all problems are reported at once (400). */
    void validate(CollectiveCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Map<String, String> errors = new LinkedHashMap<>();
        if (command.operation() == null) {
            errors.put("operation", "operation is required");
        }
        Integer count = command.count();
        if (count == null) {
            errors.put("count", "count is required");
        } else if (count < 1 || count > properties.maxPayloadElements()) {
            errors.put("count", "count must be 1-" + properties.maxPayloadElements() + ", was " + count);
        }
        Long seed = command.seed();
        if (seed != null && (seed < 0 || seed > MAX_SEED)) {
            errors.put("seed", "seed must be 0-" + MAX_SEED + ", was " + seed);
        }
        if (!errors.isEmpty()) {
            throw new InvalidCallException(errors);
        }
    }

    // ------------------------------------------------------------------ events

    private void publishStarted(CollectiveRequest request, RootSource source, Set<Integer> live, long lamport) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("collectiveId", request.collectiveId());
        data.put("operation", request.operation().name());
        data.put("count", request.count());
        data.put("seed", request.seed());
        data.put("rootSource", source.name());
        data.put("liveNodeIds", List.copyOf(live));
        bus.publish(EventDraft.of(ID, request.rootNodeId(), "COLLECTIVE_STARTED", lamport)
                .withMessage("Node " + request.rootNodeId() + " started " + name(request.operation()) + " "
                        + request.collectiveId() + " of " + request.count() + " values over the live nodes " + live
                        + " (root: " + describe(source) + ")")
                .withData(data));
    }

    private void publishOutcome(CollectiveDto dto) {
        int root = dto.rootNodeId();
        long lamport = cluster.node(root).clock().current();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("collectiveId", dto.id());
        data.put("operation", dto.operation().name());
        if (dto.refusal() != null) {
            data.put("count", dto.count());
            data.put("reason", dto.refusal().reason().name());
            bus.publish(EventDraft.of(ID, root, "COLLECTIVE_REFUSED", lamport)
                    .withMessage("Node " + root + " refused " + name(dto.operation()) + " " + dto.id() + ": "
                            + dto.refusal().message())
                    .withData(data));
            return;
        }
        long delivered = count(dto, TransferStatus.DELIVERED);
        long failed = count(dto, TransferStatus.FAILED);
        long notAttempted = count(dto, TransferStatus.NOT_ATTEMPTED);
        data.put("outcome", dto.outcome().name());
        data.put("delivered", delivered);
        data.put("failed", failed);
        data.put("notAttempted", notAttempted);
        if (dto.totalNanos() != null) {
            data.put("totalNanos", dto.totalNanos());
        }
        if (dto.verification() != null && dto.verification().passed() != null) {
            data.put("passed", dto.verification().passed());
        }
        long remote = delivered + failed + notAttempted;
        bus.publish(EventDraft.of(ID, root, "COLLECTIVE_FINISHED", lamport)
                .withMessage("Node " + root + " finished " + name(dto.operation()) + " " + dto.id() + ": "
                        + dto.outcome().name().toLowerCase(Locale.ROOT) + ", " + delivered + " of " + remote
                        + " remote transfers delivered" + (dto.totalNanos() == null ? ""
                        : " in " + String.format(Locale.ROOT, "%.2f ms", dto.totalNanos() / 1_000_000d)))
                .withData(data));
    }

    private void publishRejected(String requested, String inProgress) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requested", requested);
        data.put("actionInProgress", inProgress);
        bus.publish(EventDraft.of(ID, 0, "ACTION_REJECTED_BUSY", cluster.clusterClock().current())
                .withMessage("Rejected \"" + requested + "\": the module is busy with \"" + inProgress + "\"")
                .withData(data));
    }

    private void publishExported(ClusterNode node) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("registryPort", node.ports().rmi());
        data.put("boundName", CollectiveEndpoint.BOUND_NAME);
        bus.publish(EventDraft.of(ID, node.id(), "COLLECTIVE_ENDPOINT_EXPORTED", node.clock().current())
                .withMessage("Node " + node.id() + " exported " + CollectiveEndpoint.BOUND_NAME
                        + " in its RMI registry on port " + node.ports().rmi())
                .withData(data));
    }

    // ------------------------------------------------------------------ helpers

    private ModuleActionGuard.ActionTicket begin(String action) {
        try {
            return guard.begin(action);
        } catch (ModuleBusyException busy) {
            publishRejected(action, busy.actionInProgress());
            throw busy;
        }
    }

    /** Starts RMI on every live node, then announces each CollectiveEndpoint that this start exported. */
    private void startRmi() {
        Set<Integer> before = exportedIds();
        rmi.ensureStarted();
        for (ClusterNode node : cluster.nodes()) {
            if (!before.contains(node.id()) && CollectiveEndpointExport.current(node).isPresent()) {
                publishExported(node);
            }
        }
    }

    private Set<Integer> exportedIds() {
        return cluster.nodes().stream().filter(node -> CollectiveEndpointExport.current(node).isPresent())
                .map(ClusterNode::id).collect(Collectors.toCollection(TreeSet::new));
    }

    private Set<Integer> liveIds() {
        return cluster.nodes().stream().filter(ClusterNode::isUp).map(ClusterNode::id)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static MpiNodeDto participant(ClusterNode node, RmiNodeDto rmiNode) {
        Optional<CollectiveEndpointLogic> endpoint = CollectiveEndpointExport.current(node);
        return new MpiNodeDto(node.id(), node.status(), rmiNode.state(), rmiNode.registryPort(), rmiNode.startFailure(),
                endpoint.isPresent(), node.hasRole(NodeRole.LEADER),
                endpoint.map(CollectiveEndpointLogic::storedIds).orElse(null));
    }

    private static List<OperationDto> operations(int liveCount) {
        Integer perRank = liveCount == 0 ? null : liveCount;
        return Arrays.stream(CollectiveOperation.values()).map(op -> switch (op) {
            case BROADCAST -> new OperationDto(op, "The root sends the whole vector to every other live node.", 1);
            case SCATTER -> new OperationDto(op, "The root splits the vector into one block per live node, in rank "
                    + "order, and sends each node its block.", perRank);
            case GATHER -> new OperationDto(op, "Every live node computes its own block and the root collects the "
                    + "blocks, in rank order, into one vector.", perRank);
        }).toList();
    }

    /** The limits; worstCaseTransferMillis is derived from the configured timeouts, not measured. */
    MpiLimitsDto limits() {
        int connect = rmiTransport.connectTimeoutMillis();
        int call = rmiProperties.callTimeoutMillis();
        long worstCaseTransfer = EXCHANGES_PER_TRANSFER * ((long) connect + call);
        return new MpiLimitsDto(properties.maxPayloadElements(), properties.collectiveDeadlineMillis(),
                properties.historySize(), MAX_SEED, connect, call, worstCaseTransfer,
                properties.collectiveDeadlineMillis() + worstCaseTransfer);
    }

    private static String describe(CollectiveCommand command) {
        return command.operation() + " of " + command.count() + " values"
                + (command.rootNode() == null ? "" : " from node " + command.rootNode());
    }

    private static String describe(RootSource source) {
        return switch (source) {
            case REQUESTED -> "requested";
            case LEADER -> "the leader";
            case LOWEST_LIVE_ID -> "the lowest live node id";
        };
    }

    private static String name(CollectiveOperation operation) {
        return operation.name().toLowerCase(Locale.ROOT);
    }

    private static long count(CollectiveDto dto, TransferStatus status) {
        return dto.transfers().stream().map(TransferDto::status).filter(status::equals).count();
    }

    /** For tests: holding the guard makes every action answer 409. */
    ModuleActionGuard guard() {
        return guard;
    }
}
