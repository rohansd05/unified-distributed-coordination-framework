package com.udcf.modules.matrix;

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
import com.udcf.modules.matrix.dto.ComputeDto;
import com.udcf.modules.matrix.dto.MatrixLimitsDto;
import com.udcf.modules.matrix.dto.MatrixNodeDto;
import com.udcf.modules.matrix.dto.MatrixOverviewDto;
import com.udcf.modules.matrix.dto.MatrixRunCommand;
import com.udcf.modules.matrix.dto.MatrixRunDto;
import com.udcf.modules.matrix.dto.MatrixSeriesDto;
import com.udcf.modules.mpi.CollectiveEndpointExport;
import com.udcf.modules.mpi.CollectiveEndpointLogic;
import com.udcf.modules.mpi.CollectiveTransport;
import com.udcf.modules.mpi.MpiModule;
import com.udcf.modules.mpi.MpiProperties;
import com.udcf.modules.mpi.MpiRoleSelector;
import com.udcf.modules.mpi.RmiCollectiveTransport;
import com.udcf.modules.mpi.dto.RootSource;
import com.udcf.modules.multithreading.MultithreadingProperties;
import com.udcf.modules.multithreading.RequestsNodeService;
import com.udcf.modules.rmi.InvalidCallException;
import com.udcf.modules.rmi.PayloadSizer;
import com.udcf.modules.rmi.RmiModule;
import com.udcf.modules.rmi.RmiProperties;
import com.udcf.modules.rmi.RmiTransportProperties;
import com.udcf.modules.rmi.dto.RmiNodeDto;
import com.udcf.web.NodeStateConflictException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Experiment 10 (lab 10) on the shared cluster: one parallel matrix multiplication per request, run by E10b's
 * {@link ParallelMatrixExecutor} over Experiment 9's collectives and Experiment 1's RMI (link L6), every block
 * multiplied on its node's Experiment 2 executor, and the result compared bit for bit with the sequential
 * product.
 *
 * <p><b>Participants and root.</b> Every cluster node takes part. A node crashed at the start is skipped
 * (listed, never hidden). The root is the requested node, otherwise the live leader, otherwise the lowest live
 * node id (Experiment 9's {@link MpiRoleSelector}, TODO(L1) there).</p>
 *
 * <p><b>RMI and Experiment 2.</b> Experiment 1 owns the RMI services: every action first calls
 * {@link RmiModule#ensureStarted()}. Before every run, and at start, the module makes sure each live node's
 * request listener runs (an untimed prepare step that never throws), so no listener start is timed
 * ({@link MatrixTexts#FIRST_RUN_NOTE}). Reads never start anything (R10).</p>
 *
 * <p><b>Concurrency.</b> Start and run each hold this module's own {@link ModuleActionGuard} for their whole
 * duration, on the request thread (a second request gets 409 and is never queued; it publishes
 * {@code ACTION_REJECTED_BUSY}). The guard is not shared with Experiment 9; other activity on the cluster is not
 * paused ({@link MatrixTexts#OTHER_ACTIVITY}). The dispatch pool is created by the first start or run and closed
 * by {@link #close()}.</p>
 *
 * <p><b>Events</b> (module {@value #ID}) never tick a clock: {@code MATRIX_ENDPOINT_EXPORTED} (the started node,
 * its clock), {@code MATRIX_RUN_STARTED} (root, its clock before the run), then {@code MATRIX_RUN_FINISHED} or
 * {@code MATRIX_RUN_REFUSED} (root, its clock afterwards), and {@code ACTION_REJECTED_BUSY} (node 0, the cluster
 * clock). No seed derived from the user's seed is ever published.</p>
 *
 * <p><b>Status.</b> BUSY while an action holds the guard; otherwise RUNNING while any node exports its
 * MatrixEndpoint; otherwise IDLE.</p>
 */
@Component
public class MatrixModule implements ExperimentModule, AutoCloseable {

    public static final String ID = "matrix";
    static final String TITLE = "Parallel Matrix Multiplication";

    private static final Logger log = LoggerFactory.getLogger(MatrixModule.class);

    /** What tests replace: the transports, each node's executor, the prepare step, time and processors. */
    record Seams(CollectiveTransport collectives, MatrixTransport matrices, Function<ClusterNode, NodeExecutor> executors,
                 Consumer<ClusterNode> prepare, LongSupplier nanoClock, IntSupplier processors) {

        Seams {
            Objects.requireNonNull(collectives, "collectives must not be null");
            Objects.requireNonNull(matrices, "matrices must not be null");
            Objects.requireNonNull(executors, "executors must not be null");
            Objects.requireNonNull(prepare, "prepare must not be null");
            Objects.requireNonNull(nanoClock, "nanoClock must not be null");
            Objects.requireNonNull(processors, "processors must not be null");
        }
    }

    private final Cluster cluster;
    private final ClusterEventBus bus;
    private final MatrixRunProperties run;
    private final MatrixModuleProperties module;
    private final MpiProperties mpi;
    private final RmiModule rmi;
    private final RmiProperties rmiProperties;
    private final RmiTransportProperties rmiTransport;
    private final Clock clock;
    private final Seams seams;
    private final MatrixLimits limits;
    private final MatrixRunBudget budget;
    private final SpeedupBasis speedupBasis;
    private final MatrixHistory history;
    private final MatrixMetrics metrics;
    private final ModuleActionGuard guard = new ModuleActionGuard(ID);
    private MatrixDispatchPool pool;              // guarded by this; created by the first start or run
    private ParallelMatrixExecutor executor;      // guarded by this; built with the pool

    @Autowired
    public MatrixModule(Cluster cluster, ClusterEventBus bus, MatrixProperties matrix, MatrixRunProperties run,
                        MatrixModuleProperties module, MpiProperties mpi, RmiModule rmi, RmiProperties rmiProperties,
                        RmiTransportProperties rmiTransport, MultithreadingProperties multithreading,
                        MeterRegistry meterRegistry, Clock clock) {
        this(cluster, bus, matrix, run, module, mpi, rmi, rmiProperties, rmiTransport, meterRegistry, clock,
                new Seams(RmiCollectiveTransport.of(rmi), RmiMatrixTransport.of(rmi),
                        node -> NodeExecutor.experimentTwo(node, multithreading, meterRegistry, bus),
                        node -> RequestsNodeService.on(node, multithreading, meterRegistry, bus),
                        System::nanoTime, Runtime.getRuntime()::availableProcessors));
    }

    /** For tests: scripted transports, executors, prepare step, time and processors. */
    MatrixModule(Cluster cluster, ClusterEventBus bus, MatrixProperties matrix, MatrixRunProperties run,
                 MatrixModuleProperties module, MpiProperties mpi, RmiModule rmi, RmiProperties rmiProperties,
                 RmiTransportProperties rmiTransport, MeterRegistry meterRegistry, Clock clock, Seams seams) {
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.run = Objects.requireNonNull(run, "run must not be null");
        this.module = Objects.requireNonNull(module, "module must not be null");
        this.mpi = Objects.requireNonNull(mpi, "mpi must not be null");
        this.rmi = Objects.requireNonNull(rmi, "rmi must not be null");
        this.rmiProperties = Objects.requireNonNull(rmiProperties, "rmiProperties must not be null");
        this.rmiTransport = Objects.requireNonNull(rmiTransport, "rmiTransport must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.seams = Objects.requireNonNull(seams, "seams must not be null");
        this.limits = new MatrixLimits(matrix, mpi);   // checks the series against the derived maximum (startup)
        if (module.historySize() < limits.seriesSizes().size()) {
            throw new IllegalArgumentException("udcf.matrix.history-size " + module.historySize() + " is below the "
                    + limits.seriesSizes().size() + " series sizes " + limits.seriesSizes());
        }
        this.budget = MatrixRunBudget.of(rmiProperties, rmiTransport, mpi, run);
        this.speedupBasis = new SpeedupBasis(seams.processors());
        this.history = new MatrixHistory(module.historySize());
        this.metrics = new MatrixMetrics(meterRegistry);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int labNumber() {
        return 10;
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
     * Clean slate: forgets every run (run ids restart at 1; one still running cannot record itself). Never
     * throws, never takes the guard, publishes nothing. Stopping RMI, which drops the nodes' stored inputs and
     * kept blocks, is Experiment 1's reset; the request listeners keep running.
     */
    @Override
    public void reset() {
        history.clear();
    }

    /**
     * Closes the dispatch pool, if one was created (5 s bound). Idempotent. Called by Spring on shutdown,
     * before RMI stops.
     *
     * @throws IllegalStateException if the dispatch threads did not end within 5 s
     */
    @Override
    public synchronized void close() {
        MatrixDispatchPool closing = pool;
        pool = null;
        executor = null;
        if (closing != null) {
            closing.close();
        }
    }

    // ------------------------------------------------------------------ reads (no guard, never start)

    /** Everything the page needs to offer a run. No remote call; never starts a service. */
    public MatrixOverviewDto overview() {
        Map<Integer, RmiNodeDto> rmiNodes = rmi.overview().nodes().stream()
                .collect(Collectors.toMap(RmiNodeDto::nodeId, Function.identity()));
        Set<Integer> live = liveIds();
        Optional<MpiRoleSelector.Choice> root = MpiRoleSelector.select(null, cluster.leaderId(), live);
        List<MatrixNodeDto> participants = cluster.nodes().stream()
                .map(node -> participant(node, rmiNodes.get(node.id())))
                .toList();
        boolean anyLive = !live.isEmpty();
        int warmup = run.warmupProducts();
        int firstPort = cluster.nodes().stream().mapToInt(node -> node.ports().requests()).min().orElseThrow();
        int lastPort = cluster.nodes().stream().mapToInt(node -> node.ports().requests()).max().orElseThrow();
        return new MatrixOverviewDto(status(), guard.currentAction().orElse(null), MatrixEndpoint.BOUND_NAME, limits(),
                participants, root.map(MpiRoleSelector.Choice::nodeId).orElse(null),
                root.map(MpiRoleSelector.Choice::source).orElse(null),
                anyLive ? Math.max(limits.minSize(), live.size()) : null, speedupBasis.processors(),
                anyLive ? speedupBasis.ceiling(live.size()) : null,
                anyLive ? MatrixTexts.speedupBasis(speedupBasis, live.size(), warmup) : null,
                MatrixTexts.ALGORITHM_NOTE, MatrixTexts.PHASE_TIME_LABEL, MatrixTexts.PAYLOAD_GENERATION_NOTE,
                MatrixTexts.fairness(warmup), MatrixTexts.OTHER_ACTIVITY,
                MatrixTexts.experimentTwoSideEffect(firstPort, lastPort), MatrixTexts.FIRST_RUN_NOTE,
                MatrixTexts.COLLECTIVE_IDS_NOTE, RmiCollectiveTransport.LATENCY_BASIS, MatrixTexts.COMPUTE_ROUND_TRIP_BASIS,
                MatrixTexts.GATHER_LATENCY_BASIS, PayloadSizer.LABEL, MatrixTexts.SERIES_RULE, MatrixTexts.GLOSSARY,
                MatrixTexts.CO_HOSTING, history.latest().orElse(null));
    }

    /** The kept runs, newest first. */
    public List<MatrixRunDto> history() {
        return history.all();
    }

    /** @throws UnknownRunException (with a null id) if nothing is kept */
    public MatrixRunDto latest() {
        return history.latest().orElseThrow(() -> new UnknownRunException(null));
    }

    /** @throws UnknownRunException if no kept run has this id */
    public MatrixRunDto run(long id) {
        return history.find(id).orElseThrow(() -> new UnknownRunException(id));
    }

    /** The speedup series over the configured sizes, from the kept runs ({@link MatrixSeries}). */
    public MatrixSeriesDto series() {
        return MatrixSeries.from(history.all(), limits.seriesSizes(), speedupBasis, run.warmupProducts());
    }

    // ------------------------------------------------------------------ actions (guarded)

    /**
     * Starts RMI on every node that is up, creates the dispatch pool and starts every live node's request
     * listener (Experiment 2's executor). Idempotent.
     *
     * @throws ModuleBusyException another action is running (409)
     */
    public MatrixOverviewDto start() {
        try (ModuleActionGuard.ActionTicket ignored = begin("Start RMI and the request listeners on every live node for "
                + "matrix runs")) {
            startRmi();
            executor();
            prepare(liveIds());
        }
        return overview();
    }

    /**
     * Runs one parallel multiplication of one size from its root. COMPLETE, PARTIAL, FAILED and REFUSED (fewer
     * rows than live nodes) are all results (200).
     *
     * @throws InvalidCallException        a field is missing or out of range (400)
     * @throws com.udcf.core.cluster.UnknownNodeException the requested root does not exist (404)
     * @throws ModuleBusyException         another action is running (409)
     * @throws NodeDownException           the requested root is crashed (409)
     * @throws NodeStateConflictException  no root was requested and every node is crashed (409)
     */
    public MatrixRunDto run(MatrixRunCommand command) {
        validate(command);
        Integer requested = command.rootNode();
        if (requested != null) {
            cluster.node(requested);   // 404 for an unknown node, before taking the guard
        }
        try (ModuleActionGuard.ActionTicket ignored = begin(describe(command))) {
            if (requested != null && !cluster.node(requested).isUp()) {
                throw new NodeDownException(requested);   // a dead root cannot multiply or send
            }
            startRmi();
            Set<Integer> live = liveIds();
            MpiRoleSelector.Choice root = MpiRoleSelector.select(requested, cluster.leaderId(), live)
                    .orElseThrow(() -> new NodeStateConflictException(0,
                            "Every node is crashed, so no node can be the root of a matrix run"));
            prepare(live);
            return execute(command, root, live);
        }
    }

    private MatrixRunDto execute(MatrixRunCommand command, MpiRoleSelector.Choice root, Set<Integer> live) {
        ParallelMatrixExecutor runner = executor();
        MatrixHistory.Slot slot = history.reserve();
        MatrixRunResult result;
        MatrixRunDto dto;
        try {
            long seed = command.seed() == null ? slot.id() : command.seed();
            ClusterNode rootNode = cluster.node(root.nodeId());
            List<Integer> participants = cluster.nodes().stream().map(ClusterNode::id).toList();
            MatrixRunRequest request = new MatrixRunRequest(slot.id(), command.size(), seed, root.nodeId(), participants,
                    live);
            Instant startedAt = clock.instant();
            publishStarted(request, root.source(), live, rootNode.clock().current());
            result = runner.run(request, rootNode.clock(), seams.collectives(), seams.matrices(),
                    seams.executors().apply(rootNode));
            dto = MatrixDtoMapper.run(startedAt, root.source(), result, speedupBasis);
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
    void validate(MatrixRunCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        Map<String, String> errors = new LinkedHashMap<>();
        Integer size = command.size();
        if (size == null) {
            errors.put("size", "size is required");
        } else if (size < limits.minSize() || size > limits.maxSize()) {
            errors.put("size", "size must be " + limits.minSize() + "-" + limits.maxSize() + ", was " + size);
        }
        Long seed = command.seed();
        if (seed != null && (seed < 0 || seed > MpiModule.MAX_SEED)) {
            errors.put("seed", "seed must be 0-" + MpiModule.MAX_SEED + ", was " + seed);
        }
        if (!errors.isEmpty()) {
            throw new InvalidCallException(errors);
        }
    }

    // ------------------------------------------------------------------ events

    private void publishStarted(MatrixRunRequest request, RootSource source, Set<Integer> live, long lamport) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", request.runId());
        data.put("size", request.n());
        data.put("seed", request.seed());
        data.put("rootSource", source.name());
        data.put("liveNodeIds", List.copyOf(live));
        bus.publish(EventDraft.of(ID, request.rootNodeId(), "MATRIX_RUN_STARTED", lamport)
                .withMessage("Node " + request.rootNodeId() + " started matrix run " + request.runId() + " of size "
                        + request.n() + " over the live nodes " + live + " (root: " + describe(source) + ")")
                .withData(data));
    }

    private void publishOutcome(MatrixRunDto dto) {
        int root = dto.rootNodeId();
        long lamport = cluster.node(root).clock().current();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", dto.runId());
        data.put("size", dto.size());
        if (dto.refusal() != null) {
            data.put("reason", dto.refusal().reason().name());
            bus.publish(EventDraft.of(ID, root, "MATRIX_RUN_REFUSED", lamport)
                    .withMessage("Node " + root + " refused matrix run " + dto.runId() + ": " + dto.refusal().message())
                    .withData(data));
            return;
        }
        long done = count(dto, ComputeRecord.Status.DONE);
        long failed = count(dto, ComputeRecord.Status.FAILED);
        long notAttempted = count(dto, ComputeRecord.Status.NOT_ATTEMPTED);
        Long parallel = dto.timing().measured().parallelTotalNanos();
        Long sequential = dto.timing().measured().sequentialNanos();
        Double speedup = dto.timing().derived().speedup();
        data.put("outcome", dto.outcome().name());
        data.put("computesDone", done);
        data.put("computesFailed", failed);
        data.put("computesNotAttempted", notAttempted);
        if (parallel != null) {
            data.put("parallelTotalNanos", parallel);
        }
        if (sequential != null) {
            data.put("sequentialNanos", sequential);
        }
        if (speedup != null) {
            data.put("speedup", speedup);
        }
        if (dto.verification().passed() != null) {
            data.put("passed", dto.verification().passed());
        }
        bus.publish(EventDraft.of(ID, root, "MATRIX_RUN_FINISHED", lamport)
                .withMessage("Node " + root + " finished matrix run " + dto.runId() + " of size " + dto.size() + ": "
                        + dto.outcome().name().toLowerCase(Locale.ROOT) + ", " + done + " of " + dto.computes().size()
                        + " blocks computed" + (parallel == null ? ""
                        : " in " + String.format(Locale.ROOT, "%.2f ms", parallel / 1_000_000d)))
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
        data.put("boundName", MatrixEndpoint.BOUND_NAME);
        bus.publish(EventDraft.of(ID, node.id(), "MATRIX_ENDPOINT_EXPORTED", node.clock().current())
                .withMessage("Node " + node.id() + " exported " + MatrixEndpoint.BOUND_NAME
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

    /** Starts RMI on every live node, then announces each MatrixEndpoint that this start exported. */
    private void startRmi() {
        Set<Integer> before = exportedIds();
        rmi.ensureStarted();
        for (ClusterNode node : cluster.nodes()) {
            if (!before.contains(node.id()) && MatrixEndpointExport.current(node).isPresent()) {
                publishExported(node);
            }
        }
    }

    /**
     * Makes sure each live node's request listener runs, outside every timed part. Never throws: a node whose
     * listener cannot start (crashed since the snapshot, or its port taken) fails its compute later as
     * EXECUTOR_UNAVAILABLE, a reported hole.
     */
    private void prepare(Set<Integer> live) {
        for (int id : live) {
            try {
                seams.prepare().accept(cluster.node(id));
            } catch (RuntimeException e) {
                log.warn("Matrix: could not prepare the request listener of node {}: {}", id, e.toString());
            }
        }
    }

    /** The runner, with the dispatch pool created on first use (one thread per possible rank). */
    private synchronized ParallelMatrixExecutor executor() {
        if (executor == null) {
            pool = new MatrixDispatchPool(cluster.size());
            executor = new ParallelMatrixExecutor(limits, run, mpi, budget, pool, seams.nanoClock());
        }
        return executor;
    }

    private Set<Integer> exportedIds() {
        return cluster.nodes().stream().filter(node -> MatrixEndpointExport.current(node).isPresent())
                .map(ClusterNode::id).collect(Collectors.toCollection(TreeSet::new));
    }

    private Set<Integer> liveIds() {
        return cluster.nodes().stream().filter(ClusterNode::isUp).map(ClusterNode::id)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static MatrixNodeDto participant(ClusterNode node, RmiNodeDto rmiNode) {
        Optional<MatrixEndpointLogic> matrix = MatrixEndpointExport.current(node);
        return new MatrixNodeDto(node.id(), node.status(), rmiNode.state(), rmiNode.registryPort(), rmiNode.startFailure(),
                matrix.isPresent(), node.hasRole(NodeRole.LEADER),
                matrix.map(MatrixEndpointLogic::keptRunIds).orElse(null),
                CollectiveEndpointExport.current(node).map(CollectiveEndpointLogic::storedIds).orElse(null),
                RequestsNodeService.find(node).map(RequestsNodeService::isRunning).orElse(false),
                node.ports().requests());
    }

    /** The limits; the budget figures are derived from the configured timeouts, not measured. */
    MatrixLimitsDto limits() {
        return new MatrixLimitsDto(limits.minSize(), limits.maxSize(), limits.seriesSizes(), module.historySize(),
                MpiModule.MAX_SEED, run.warmupProducts(), run.computeTimeoutMillis(), run.resultStoreSize(),
                mpi.collectiveDeadlineMillis(), rmiTransport.connectTimeoutMillis(), rmiProperties.callTimeoutMillis(),
                budget.transferWorstMillis(), budget.computeWaitMillis(), budget.productAllowanceMillis(),
                budget.worstCaseRunMillis(), budget.worstCaseRunMillis());
    }

    private static String describe(MatrixRunCommand command) {
        return "Matrix run of size " + command.size() + (command.rootNode() == null ? "" : " from node " + command.rootNode());
    }

    private static String describe(RootSource source) {
        return switch (source) {
            case REQUESTED -> "requested";
            case LEADER -> "the leader";
            case LOWEST_LIVE_ID -> "the lowest live node id";
        };
    }

    private static long count(MatrixRunDto dto, ComputeRecord.Status status) {
        return dto.computes().stream().map(ComputeDto::status).filter(status::equals).count();
    }

    /** For tests: holding the guard makes every action answer 409. */
    ModuleActionGuard guard() {
        return guard;
    }
}
