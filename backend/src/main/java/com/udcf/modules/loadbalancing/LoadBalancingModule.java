package com.udcf.modules.loadbalancing;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.core.module.ExperimentModule;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.loadbalancing.dto.ActionState;
import com.udcf.modules.loadbalancing.dto.CompareCommand;
import com.udcf.modules.loadbalancing.dto.ComparisonDto;
import com.udcf.modules.loadbalancing.dto.CrashDto;
import com.udcf.modules.loadbalancing.dto.CrashPlan;
import com.udcf.modules.loadbalancing.dto.FindingDto;
import com.udcf.modules.loadbalancing.dto.LoadBalancingOverviewDto;
import com.udcf.modules.loadbalancing.dto.NodeResultDto;
import com.udcf.modules.loadbalancing.dto.PhaseReportDto;
import com.udcf.modules.loadbalancing.dto.RunCommand;
import com.udcf.modules.loadbalancing.dto.RunDto;
import com.udcf.modules.loadbalancing.dto.StrategyDto;
import com.udcf.modules.loadbalancing.dto.WorkerDto;
import com.udcf.modules.multithreading.MultithreadingProperties;
import com.udcf.web.InvalidParameterException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Experiment 6 (lab 6) on the shared cluster: run one strategy, "compare all four", and crash
 * a worker mid-run. Each run dispatches over TCP into the nodes' Exp 2 executors (link L3,
 * through {@link LoadBalancingGateway}).
 *
 * <p><b>Actions.</b> A run and a comparison each take the module's {@link ModuleActionGuard}
 * before their background thread starts, and the thread releases it in {@code finally}; if the
 * thread cannot start, the guard is released at once and nothing is left RUNNING. So the
 * module reads BUSY for exactly as long as an action executes, and a second action gets HTTP
 * 409. Every parameter is checked before the guard is taken or any event is published, so a
 * rejected request changes nothing.</p>
 *
 * <p><b>Crash plan.</b> A run may name a node to crash after a number of served requests. The
 * module injects that crash itself, on the client thread that served that request, through
 * {@code Cluster.crash} (a real crash on every protocol, R10); the node stays down after the
 * run. It is never automatic and never the balancer's doing.</p>
 *
 * <p><b>Comparison.</b> One unreported warm-up batch with the same settings (round robin), then
 * the four strategies in {@link Strategy} order, measured as they are; the finding comes from
 * {@link StrategyComparison} and claims nothing it did not measure.</p>
 *
 * <p><b>Events</b> (module {@value #ID}, node 0, the cluster clock), each with a runId or a
 * comparisonId: RUN_STARTED, CRASH_INJECTED or CRASH_SKIPPED, RUN_FINISHED, RUN_FAILED,
 * COMPARISON_STARTED, COMPARISON_FINISHED; the warm-up publishes no RUN_* events. The
 * transport's DISPATCH_* events carry the runId too.</p>
 *
 * <p><b>Metrics.</b> Each run (warm-up included: its dispatches are real) is added to
 * {@link LoadBalancingMetrics} exactly once when it ends, also when it fails.</p>
 */
@Component
public class LoadBalancingModule implements ExperimentModule {

    public static final String ID = TcpWorkerTransport.MODULE;

    static final String CAPACITY_NOTE = "The capacity profiles are set in the configuration, not measured. "
            + "Every node runs on the same computer, so a slow node is slow because it has fewer threads "
            + "and more work per request, not because its hardware is slower.";
    static final String WORK_UNITS_NOTE = "One work unit is 40 rounds of a cryptographic hash on the worker, "
            + "multiplied by that node's work multiplier (1, 2 or 4). The work is real computation, not a pause.";
    static final String DELIVERY_NOTE = "Delivery is at least once: if a worker takes longer than the timeout "
            + "to answer, the balancer sends the request to another worker, although the first one may still "
            + "finish it.";
    static final String WARM_UP_NOTE = "Before a comparison, the module sends one extra batch with the same "
            + "settings and does not count it, so the strategy that goes first is not slowed down by a cold start.";
    static final String CRASH_NOTE = "A crash during a run is injected by this module once the chosen number of "
            + "requests has been served. It is a real crash of that node on every protocol, and the node stays "
            + "down until you recover it. Nothing crashes by itself.";

    static final List<StrategyDto> STRATEGIES = List.of(
            new StrategyDto(Strategy.ROUND_ROBIN,
                    "Sends each request to the next worker in turn, and starts again after the last one.",
                    "Nothing about the workers: only whose turn it is."),
            new StrategyDto(Strategy.WEIGHTED_ROUND_ROBIN,
                    "Takes turns too, but gives each worker a share of the turns in proportion to its weight "
                            + "(4, 2 or 1, its thread count).",
                    "A fixed weight for each worker, set before the run starts. It never changes during the run."),
            new StrategyDto(Strategy.LEAST_CONNECTIONS,
                    "Sends each request to the worker with the fewest requests still waiting for an answer.",
                    "How many requests each worker has in progress right now, counted by the balancer itself."),
            new StrategyDto(Strategy.LEAST_RESPONSE_TIME,
                    "Sends each request to the worker expected to finish it soonest: its recent response time "
                            + "multiplied by its requests in progress plus one.",
                    "How long each worker has recently taken to answer, and how many requests it has in "
                            + "progress right now."));

    /** Each action on its own virtual thread; it only waits for the run's client threads. */
    static final Executor VIRTUAL_THREAD_PER_ACTION = task -> Thread.ofVirtual().name("udcf-lb-action").start(task);

    private static final Logger log = LoggerFactory.getLogger(LoadBalancingModule.class);

    private final Cluster cluster;
    private final ClusterEventBus bus;
    private final LoadBalancingProperties properties;
    private final LoadBalancingGateway gateway;
    private final Executor actionExecutor;
    private final LoadBalancingMetrics metrics;
    private final ModuleActionGuard guard = new ModuleActionGuard(ID);
    private final Object recordLock = new Object();

    private volatile RunDto latestRun;
    private volatile ComparisonDto latestComparison;
    private List<WorkerInfo> lastRecorded;   // guarded by recordLock

    @Autowired
    public LoadBalancingModule(Cluster cluster, MultithreadingProperties multithreadingProperties,
                               MeterRegistry meterRegistry, ClusterEventBus bus,
                               LoadBalancingProperties properties) {
        this(cluster, bus, meterRegistry, properties,
                new LoadBalancingGateway(cluster, multithreadingProperties, meterRegistry, bus, properties),
                VIRTUAL_THREAD_PER_ACTION);
    }

    /** For tests: a chosen gateway and action executor. */
    LoadBalancingModule(Cluster cluster, ClusterEventBus bus, MeterRegistry meterRegistry,
                        LoadBalancingProperties properties, LoadBalancingGateway gateway, Executor actionExecutor) {
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.actionExecutor = Objects.requireNonNull(actionExecutor, "actionExecutor must not be null");
        this.metrics = new LoadBalancingMetrics(meterRegistry, cluster, gateway::workers);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int labNumber() {
        return 6;
    }

    @Override
    public String title() {
        return "Load Balancing";
    }

    /** BUSY while a run or comparison executes, IDLE otherwise. */
    @Override
    public ModuleStatus status() {
        return guard.isBusy() ? ModuleStatus.BUSY : ModuleStatus.IDLE;
    }

    /** Forgets the latest run and comparison and gives every worker fresh counters. Called only while idle. */
    @Override
    public void reset() {
        latestRun = null;
        latestComparison = null;
        gateway.resetWorkers();
    }

    // ------------------------------------------------------------------ actions

    /**
     * Starts one run in the background and returns it as RUNNING.
     *
     * @throws InvalidParameterException a parameter outside its limit (HTTP 400)
     * @throws com.udcf.core.cluster.UnknownNodeException the crash plan names no such node (404)
     * @throws NodeDownException the crash plan names a node that is already down (409)
     * @throws com.udcf.core.module.ModuleBusyException another action is running (409)
     * @throws java.util.concurrent.RejectedExecutionException the action could not start; nothing changed
     */
    public RunDto startRun(RunCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (command.strategy() == null) {
            throw new InvalidParameterException("strategy", "is required");
        }
        checkCounts(command.requestCount(), command.workUnits(), command.concurrency(), 1);
        CrashPlan plan = command.crash();
        if (plan != null) {
            checkCrashPlan(plan, command.requestCount());
        }

        String runId = newId();
        ModuleActionGuard.ActionTicket ticket = guard.begin("Load balancing run with "
                + label(command.strategy()) + ", " + command.requestCount() + " requests");
        RunDto started = new RunDto(runId, ActionState.RUNNING, command.strategy(), command.requestCount(),
                command.workUnits(), command.concurrency(),
                plan == null ? null : new CrashDto(plan.nodeId(), plan.afterServed(), false),
                Instant.now(), null, null, null);
        RunDto previous = latestRun;
        latestRun = started;
        try {
            actionExecutor.execute(() -> {
                try {
                    executeRun(started, plan);
                } finally {
                    ticket.close();
                }
            });
        } catch (RuntimeException e) {
            latestRun = previous;
            ticket.close();
            throw e;
        }
        return started;
    }

    /**
     * Starts "compare all four" in the background and returns it as RUNNING. Same errors as
     * {@link #startRun}, except that a comparison has no crash plan; the total-work cap applies
     * to all {@value LoadBalancingProperties#COMPARISON_BATCHES} batches together.
     */
    public ComparisonDto startComparison(CompareCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        checkCounts(command.requestCount(), command.workUnits(), command.concurrency(),
                LoadBalancingProperties.COMPARISON_BATCHES);

        String comparisonId = newId();
        ModuleActionGuard.ActionTicket ticket = guard.begin("Comparison of all four strategies, "
                + command.requestCount() + " requests each");
        ComparisonDto started = new ComparisonDto(comparisonId, ActionState.RUNNING, command.requestCount(),
                command.workUnits(), command.concurrency(), command.requestCount(), Instant.now(), null,
                List.of(), null, null);
        ComparisonDto previous = latestComparison;
        latestComparison = started;
        try {
            actionExecutor.execute(() -> {
                try {
                    executeComparison(started);
                } finally {
                    ticket.close();
                }
            });
        } catch (RuntimeException e) {
            latestComparison = previous;
            ticket.close();
            throw e;
        }
        return started;
    }

    /** Status, settings, texts, every worker with its live counters, and the latest results. */
    public LoadBalancingOverviewDto overview() {
        List<WorkerDto> workers = gateway.workers().stream().map(this::workerDto).toList();
        return new LoadBalancingOverviewDto(status(), guard.currentAction().orElse(null), properties.defaults(),
                properties.limits(), CAPACITY_NOTE, WORK_UNITS_NOTE, DELIVERY_NOTE, WARM_UP_NOTE, CRASH_NOTE,
                STRATEGIES, workers, latestRun, latestComparison);
    }

    // ------------------------------------------------------------------ background work

    private void executeRun(RunDto started, CrashPlan plan) {
        String runId = started.runId();
        Strategy strategy = started.strategy();
        CrashTrigger trigger = plan == null ? null : new CrashTrigger(plan.afterServed(),
                () -> cluster.crash(plan.nodeId()), crashed -> publishCrash(runId, plan, crashed));
        Consumer<DispatchResult> observer = trigger == null ? result -> { } : trigger;

        Map<String, Object> data = idData("runId", runId);
        data.put("kind", "RUN");
        data.put("strategy", strategy.name());
        putCounts(data, started.requestCount(), started.workUnits(), started.concurrency());
        if (plan != null) {
            data.put("crashNodeId", plan.nodeId());
            data.put("crashAfterServed", plan.afterServed());
        }
        publish("RUN_STARTED", "Load balancing run started with " + label(strategy), null, data);
        try {
            PhaseReport report = gateway.run(strategy, started.requestCount(), started.workUnits(),
                    started.concurrency(), runId, observer);
            List<WorkerInfo> workers = gateway.workers();
            record(strategy, workers, report.makespanMillis());
            PhaseReportDto dto = phaseDto(runId, report, workers);
            latestRun = new RunDto(runId, ActionState.FINISHED, strategy, started.requestCount(), started.workUnits(),
                    started.concurrency(), crashDto(plan, trigger), started.startedAt(), Instant.now(), dto, null);
            publishFinished(runId, null, "RUN", report);
        } catch (RuntimeException e) {
            record(strategy, gateway.workers(), null);
            latestRun = new RunDto(runId, ActionState.FAILED, strategy, started.requestCount(), started.workUnits(),
                    started.concurrency(), crashDto(plan, trigger), started.startedAt(), Instant.now(), null,
                    messageOf(e));
            publishFailed(idData("runId", runId), "RUN", e);
        }
    }

    private void executeComparison(ComparisonDto started) {
        String comparisonId = started.comparisonId();
        int n = started.requestCount();
        int units = started.workUnits();
        int clients = started.concurrency();

        Map<String, Object> startData = idData("comparisonId", comparisonId);
        putCounts(startData, n, units, clients);
        startData.put("warmUpRequests", started.warmUpRequests());
        publish("COMPARISON_STARTED", "Comparison of all four strategies started", null, startData);

        Strategy current = Strategy.ROUND_ROBIN;
        List<PhaseReport> reports = new ArrayList<>();
        List<PhaseReportDto> phases = new ArrayList<>();
        try {
            // Unreported warm-up: real work, counted in the metrics, never in the comparison.
            PhaseReport warmUp = gateway.run(Strategy.ROUND_ROBIN, n, units, clients, comparisonId + "-warm-up",
                    result -> { });
            record(Strategy.ROUND_ROBIN, gateway.workers(), warmUp.makespanMillis());

            for (Strategy strategy : Strategy.values()) {
                current = strategy;
                String runId = newId();
                Map<String, Object> data = idData("runId", runId);
                data.put("comparisonId", comparisonId);
                data.put("kind", "COMPARISON");
                data.put("strategy", strategy.name());
                putCounts(data, n, units, clients);
                publish("RUN_STARTED", "Comparison phase started with " + label(strategy), null, data);

                PhaseReport report = gateway.run(strategy, n, units, clients, runId, result -> { });
                List<WorkerInfo> workers = gateway.workers();
                record(strategy, workers, report.makespanMillis());
                reports.add(report);
                phases.add(phaseDto(runId, report, workers));
                latestComparison = comparison(started, ActionState.RUNNING, phases, null, null);
                publishFinished(runId, comparisonId, "COMPARISON", report);
            }

            StrategyComparison measured = new StrategyComparison(reports);
            FindingDto finding = new FindingDto(
                    measured.fastest().map(PhaseReport::strategy).orElse(null),
                    measured.slowest().map(PhaseReport::strategy).orElse(null),
                    measured.mostEven().map(PhaseReport::strategy).orElse(null),
                    measured.roundRobinFinishedLast(), measured.roundRobinMostEven(),
                    round(measured.gainOverRoundRobinPercent()));
            latestComparison = comparison(started, ActionState.FINISHED, phases, finding, null);

            Map<String, Object> data = idData("comparisonId", comparisonId);
            data.put("fastest", name(finding.fastest()));
            data.put("slowest", name(finding.slowest()));
            data.put("mostEven", name(finding.mostEven()));
            data.put("roundRobinFinishedLast", finding.roundRobinFinishedLast());
            data.put("roundRobinMostEven", finding.roundRobinMostEven());
            if (finding.gainOverRoundRobinPercent() != null) {
                data.put("gainOverRoundRobinPercent", finding.gainOverRoundRobinPercent());
            }
            publish("COMPARISON_FINISHED", "Comparison of all four strategies finished", null, data);
        } catch (RuntimeException e) {
            record(current, gateway.workers(), null);
            latestComparison = comparison(started, ActionState.FAILED, phases, null, messageOf(e));
            publishFailed(idData("comparisonId", comparisonId), "COMPARISON", e);
        }
    }

    /** Adds a run's worker counters to the metrics once; a list already added is skipped. */
    private void record(Strategy strategy, List<WorkerInfo> workers, Double makespanMillis) {
        synchronized (recordLock) {
            if (workers == lastRecorded) {
                return;
            }
            lastRecorded = workers;
        }
        metrics.recordRun(strategy, workers, makespanMillis);
    }

    // ------------------------------------------------------------------ validation

    private void checkCounts(int requestCount, int workUnits, int concurrency, int batches) {
        LoadBalancingProperties.Limits limits = properties.limits();
        checkRange("requestCount", requestCount, limits.maxRequestCount());
        checkRange("workUnits", workUnits, limits.maxWorkUnits());
        checkRange("concurrency", concurrency, limits.maxConcurrency());
        long total = (long) batches * requestCount * workUnits;
        if (total > limits.maxTotalWork()) {
            throw new InvalidParameterException("totalWork", (batches == 1 ? "" : batches + " x ")
                    + "requestCount x workUnits must not exceed " + limits.maxTotalWork() + ", was " + total);
        }
    }

    private static void checkRange(String name, int value, int max) {
        if (value < 1 || value > max) {
            throw new InvalidParameterException(name, "must be between 1 and " + max + ", was " + value);
        }
    }

    private void checkCrashPlan(CrashPlan plan, int requestCount) {
        if (plan.afterServed() < 1 || plan.afterServed() > requestCount - 1) {
            throw new InvalidParameterException("crash.afterServed", requestCount < 2
                    ? "needs a run of at least 2 requests"
                    : "must be between 1 and " + (requestCount - 1) + ", was " + plan.afterServed());
        }
        ClusterNode node = cluster.node(plan.nodeId());   // UnknownNodeException: 404
        if (!node.isUp()) {
            throw new NodeDownException(node.id());
        }
    }

    // ------------------------------------------------------------------ DTOs

    private WorkerDto workerDto(WorkerInfo w) {
        ClusterNode node = cluster.node(w.nodeId());
        return new WorkerDto(w.nodeId(), node.status(), node.capacity(), node.capacity().threads(),
                node.capacity().workMultiplier(), w.weight(), w.port(), node.isUp() && w.isHealthy(),
                w.inFlight(), w.completed(), w.failed(), w.declined(),
                round(w.ewmaLatencyMillis()), round(w.averageLatencyMillis()));
    }

    private PhaseReportDto phaseDto(String runId, PhaseReport report, List<WorkerInfo> workers) {
        Map<Integer, Integer> perNode = report.requestsPerNode();
        Map<Integer, OptionalDouble> averages = report.averageLatencyByNode();
        List<NodeResultDto> nodes = new ArrayList<>();
        for (WorkerInfo w : workers) {
            nodes.add(new NodeResultDto(w.nodeId(), cluster.node(w.nodeId()).capacity(),
                    perNode.getOrDefault(w.nodeId(), 0), round(averages.getOrDefault(w.nodeId(), OptionalDouble.empty())),
                    w.failed(), w.declined(), w.isHealthy()));
        }
        return new PhaseReportDto(runId, report.strategy(), report.total(), report.served(), report.failures(),
                report.reroutes(), round2(report.makespanMillis()), round(report.averageLatency()),
                round(report.p95Latency()), round(report.maxLatency()), report.loadSpread(), nodes);
    }

    private static CrashDto crashDto(CrashPlan plan, CrashTrigger trigger) {
        return plan == null ? null : new CrashDto(plan.nodeId(), plan.afterServed(), trigger.crashed());
    }

    private static ComparisonDto comparison(ComparisonDto started, ActionState state, List<PhaseReportDto> phases,
                                            FindingDto finding, String error) {
        return new ComparisonDto(started.comparisonId(), state, started.requestCount(), started.workUnits(),
                started.concurrency(), started.warmUpRequests(), started.startedAt(),
                state == ActionState.RUNNING ? null : Instant.now(), List.copyOf(phases), finding, error);
    }

    // ------------------------------------------------------------------ events

    private void publishCrash(String runId, CrashPlan plan, boolean crashed) {
        Map<String, Object> data = idData("runId", runId);
        data.put("nodeId", plan.nodeId());
        data.put("afterServed", plan.afterServed());
        if (crashed) {
            publish("CRASH_INJECTED", "The module crashed node " + plan.nodeId() + " after " + plan.afterServed()
                    + " requests were served", plan.nodeId(), data);
        } else {
            data.put("reason", "node already down");
            publish("CRASH_SKIPPED", "Node " + plan.nodeId() + " was already down after " + plan.afterServed()
                    + " requests were served", plan.nodeId(), data);
        }
    }

    private void publishFinished(String runId, String comparisonId, String kind, PhaseReport report) {
        Map<String, Object> data = idData("runId", runId);
        if (comparisonId != null) {
            data.put("comparisonId", comparisonId);
        }
        data.put("kind", kind);
        data.put("strategy", report.strategy().name());
        data.put("served", report.served());
        data.put("failures", report.failures());
        data.put("reroutes", report.reroutes());
        data.put("makespanMillis", round2(report.makespanMillis()));
        data.put("loadSpread", report.loadSpread());
        Map<String, Object> perNode = new LinkedHashMap<>();
        report.requestsPerNode().forEach((node, count) -> perNode.put(String.valueOf(node), count));
        data.put("requestsPerNode", perNode);
        publish("RUN_FINISHED", "Load balancing run with " + label(report.strategy()) + " finished in "
                + round2(report.makespanMillis()) + " ms", null, data);
    }

    private void publishFailed(Map<String, Object> data, String kind, RuntimeException e) {
        log.warn("Load balancing {} failed", kind.toLowerCase(java.util.Locale.ROOT), e);
        data.put("kind", kind);
        data.put("error", messageOf(e));
        publish("RUN_FAILED", "Load balancing " + kind.toLowerCase(java.util.Locale.ROOT) + " failed", null, data);
    }

    private void publish(String type, String message, Integer peer, Map<String, Object> data) {
        bus.publish(EventDraft.of(ID, 0, type, cluster.clusterClock().tick())
                .withPeer(peer).withMessage(message).withData(data));
    }

    private static Map<String, Object> idData(String key, String id) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(key, id);
        return data;
    }

    private static void putCounts(Map<String, Object> data, int requestCount, int workUnits, int concurrency) {
        data.put("requestCount", requestCount);
        data.put("workUnits", workUnits);
        data.put("concurrency", concurrency);
    }

    // ------------------------------------------------------------------ helpers

    /** "round robin", "least response time": for sentences, never capitals. */
    static String label(Strategy strategy) {
        return strategy.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    private static String name(Strategy strategy) {
        return strategy == null ? "" : strategy.name();
    }

    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String messageOf(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static Double round(OptionalDouble value) {
        return value.isPresent() ? round2(value.getAsDouble()) : null;
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }
}
