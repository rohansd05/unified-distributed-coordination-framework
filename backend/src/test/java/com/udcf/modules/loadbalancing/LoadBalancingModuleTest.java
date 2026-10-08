package com.udcf.modules.loadbalancing;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.cluster.NodeStatus;
import com.udcf.core.cluster.UnknownNodeException;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.core.metrics.MetricNames;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.loadbalancing.dto.ActionState;
import com.udcf.modules.loadbalancing.dto.CompareCommand;
import com.udcf.modules.loadbalancing.dto.ComparisonDto;
import com.udcf.modules.loadbalancing.dto.CrashPlan;
import com.udcf.modules.loadbalancing.dto.LoadBalancingOverviewDto;
import com.udcf.modules.loadbalancing.dto.PhaseReportDto;
import com.udcf.modules.loadbalancing.dto.RunCommand;
import com.udcf.modules.loadbalancing.dto.RunDto;
import com.udcf.modules.loadbalancing.dto.StrategyDto;
import com.udcf.modules.loadbalancing.dto.WorkerDto;
import com.udcf.modules.multithreading.MultithreadingProperties;
import com.udcf.modules.multithreading.WorkloadType;
import com.udcf.web.InvalidParameterException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The module on a standalone three-node cluster (FAST, MEDIUM, SLOW) with real sockets and no
 * Spring context, so its crashes never disturb other tests. Actions run on a held executor:
 * the test decides when the background work runs, so BUSY and IDLE are checked without
 * timing.
 *
 * <p>Test-only port bases 47130 to 47630 (requests ports 47531 to 47533), inside the Exp 6
 * test range 47100 to 47899.</p>
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class LoadBalancingModuleTest {

    private static final ClusterProperties CLUSTER = new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(47130, 47230, 47330, 47430, 47530, 47630));
    private static final MultithreadingProperties MULTITHREADING =
            new MultithreadingProperties(200, 60, "udcf-worker-", 30, 500, 2000,
                    new MultithreadingProperties.Backpressure(50, WorkloadType.CPU_HASH, 200));
    private static final LoadBalancingProperties PROPERTIES = new LoadBalancingProperties(10_000,
            new LoadBalancingProperties.Defaults(60, 400, 12),
            new LoadBalancingProperties.Limits(1000, 5000, 50, 500_000));
    private static final Pattern ALL_CAPS_WORD = Pattern.compile("\\b[A-Z]{2,}\\b");

    /** Keeps submitted actions until the test runs them on its own thread. */
    private static final class HeldExecutor implements Executor {
        private final List<Runnable> tasks = new CopyOnWriteArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove(0).run();
            }
        }
    }

    private ClusterEventBus bus;
    private Cluster cluster;
    private SimpleMeterRegistry meters;
    private HeldExecutor held;
    private LoadBalancingModule module;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 5000), java.time.Clock.systemUTC());
        cluster = new Cluster(CLUSTER, bus);
        meters = new SimpleMeterRegistry();
        held = new HeldExecutor();
        module = moduleWith(gateway(), held);
    }

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    private LoadBalancingGateway gateway() {
        return new LoadBalancingGateway(cluster, MULTITHREADING, meters, bus, PROPERTIES);
    }

    private LoadBalancingModule moduleWith(LoadBalancingGateway gateway, Executor executor) {
        return new LoadBalancingModule(cluster, bus, meters, PROPERTIES, gateway, executor);
    }

    private List<ClusterEvent> events() {
        return bus.query(LoadBalancingModule.ID, null, 5000).stream()
                .filter(e -> !e.type().startsWith("DISPATCH_")).toList();
    }

    private List<ClusterEvent> events(String type) {
        return events().stream().filter(e -> e.type().equals(type)).toList();
    }

    private static RunCommand run(Strategy strategy, int requests, CrashPlan crash) {
        return new RunCommand(strategy, requests, 5, 6, crash);
    }

    private WorkerDto row(LoadBalancingOverviewDto overview, int nodeId) {
        return overview.workers().stream().filter(w -> w.nodeId() == nodeId).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ identity and overview

    @Test
    @DisplayName("lab 6, Load Balancing, IDLE, and an overview with workers 4/2/1, four strategies, settings and no results yet")
    void identityAndOverview() {
        assertThat(module.id()).isEqualTo("loadbalancing");
        assertThat(module.labNumber()).isEqualTo(6);
        assertThat(module.title()).isEqualTo("Load Balancing");
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);

        LoadBalancingOverviewDto overview = module.overview();

        assertThat(overview.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(overview.actionInProgress()).isNull();
        assertThat(overview.latestRun()).isNull();
        assertThat(overview.latestComparison()).isNull();
        assertThat(overview.defaults()).isEqualTo(PROPERTIES.defaults());
        assertThat(overview.limits()).isEqualTo(PROPERTIES.limits());
        assertThat(overview.workers()).extracting(WorkerDto::weight).containsExactly(4, 2, 1);
        assertThat(overview.workers()).extracting(WorkerDto::port).containsExactly(47531, 47532, 47533);
        assertThat(overview.workers()).allSatisfy(w -> {
            assertThat(w.healthy()).isTrue();
            assertThat(w.inFlight()).isZero();
            assertThat(w.ewmaLatencyMillis()).isNull();
            assertThat(w.averageLatencyMillis()).isNull();
        });
        assertThat(overview.strategies()).extracting(StrategyDto::strategy).containsExactly(Strategy.values());
    }

    @Test
    @DisplayName("every text a student reads is plain sentences without all-caps words")
    void plainTexts() {
        LoadBalancingOverviewDto overview = module.overview();
        List<String> texts = new ArrayList<>(List.of(overview.capacityNote(), overview.workUnitsNote(),
                overview.deliveryNote(), overview.warmUpNote(), overview.crashNote()));
        overview.strategies().forEach(s -> {
            texts.add(s.description());
            texts.add(s.informationUsed());
        });
        module.startRun(run(Strategy.LEAST_RESPONSE_TIME, 6, null));
        texts.add(module.overview().actionInProgress());
        held.runAll();
        module.startComparison(new CompareCommand(6, 5, 2));
        texts.add(module.overview().actionInProgress());
        held.runAll();

        assertThat(texts).hasSize(15).allSatisfy(t -> {
            assertThat(t).isNotBlank();
            assertThat(ALL_CAPS_WORD.matcher(t).find()).as(t).isFalse();
        });
    }

    @Test
    @DisplayName("a crashed node's worker row says healthy false and shows no invented latency")
    void crashedWorkerRow() {
        cluster.crash(2);

        WorkerDto row = row(module.overview(), 2);

        assertThat(row.nodeStatus()).isEqualTo(NodeStatus.CRASHED);
        assertThat(row.healthy()).isFalse();
        assertThat(row.ewmaLatencyMillis()).isNull();
        assertThat(row.averageLatencyMillis()).isNull();
        assertThat(row.inFlight()).isZero();
    }

    // ------------------------------------------------------------------ a run and the guard

    @Test
    @DisplayName("a run is BUSY from the moment it is accepted until it ends, then IDLE with the measured result")
    void runLifecycle() {
        AtomicReference<ModuleStatus> statusDuringRun = new AtomicReference<>();
        AtomicReference<LoadBalancingModule> self = new AtomicReference<>();
        LoadBalancingGateway observing = new LoadBalancingGateway(cluster, MULTITHREADING, meters, bus, PROPERTIES) {
            @Override
            public PhaseReport run(Strategy s, int n, int w, int c, String runId, Consumer<DispatchResult> onResult) {
                statusDuringRun.set(self.get().status());
                return super.run(s, n, w, c, runId, onResult);
            }
        };
        LoadBalancingModule m = moduleWith(observing, held);
        self.set(m);

        RunDto accepted = m.startRun(run(Strategy.ROUND_ROBIN, 60, null));

        assertThat(accepted.state()).isEqualTo(ActionState.RUNNING);
        assertThat(accepted.report()).isNull();
        assertThat(m.status()).isEqualTo(ModuleStatus.BUSY);
        assertThat(m.overview().latestRun()).isEqualTo(accepted);
        assertThat(events()).isEmpty();                        // nothing is published until it runs

        held.runAll();

        assertThat(statusDuringRun.get()).isEqualTo(ModuleStatus.BUSY);
        assertThat(m.status()).isEqualTo(ModuleStatus.IDLE);
        RunDto finished = m.overview().latestRun();
        assertThat(finished.state()).isEqualTo(ActionState.FINISHED);
        assertThat(finished.runId()).isEqualTo(accepted.runId());
        assertThat(finished.finishedAt()).isNotNull();
        PhaseReportDto report = finished.report();
        assertThat(report.failures()).isZero();
        assertThat(report.nodes()).extracting(n -> n.requests()).containsExactly(20, 20, 20);
        assertThat(report.averageLatencyMillis()).isNotNull();

        ClusterEvent started = events("RUN_STARTED").get(0);
        ClusterEvent done = events("RUN_FINISHED").get(0);
        assertThat(List.of(started, done)).allSatisfy(e -> {
            assertThat(e.nodeId()).isZero();
            assertThat(e.data()).containsEntry("runId", accepted.runId()).containsEntry("kind", "RUN");
        });
        assertThat(done.lamportTime()).isGreaterThan(started.lamportTime());
        assertThat(done.data().get("requestsPerNode")).isEqualTo(Map.of("1", 20, "2", 20, "3", 20));
    }

    @Test
    @DisplayName("a second action while one is held gets ModuleBusyException; after it runs the module is IDLE again")
    void busy() {
        module.startRun(run(Strategy.ROUND_ROBIN, 6, null));

        assertThatThrownBy(() -> module.startRun(run(Strategy.ROUND_ROBIN, 6, null)))
                .isInstanceOf(ModuleBusyException.class);
        assertThatThrownBy(() -> module.startComparison(new CompareCommand(6, 5, 2)))
                .isInstanceOf(ModuleBusyException.class);
        assertThat(module.overview().actionInProgress()).isEqualTo("Load balancing run with round robin, 6 requests");

        held.runAll();
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(module.overview().actionInProgress()).isNull();
    }

    @Test
    @DisplayName("if the action cannot start, the guard is released, nothing is left RUNNING, no event, and the call throws")
    void rejectedStart() {
        Executor refusing = task -> {
            throw new RejectedExecutionException("no threads");
        };
        LoadBalancingModule m = moduleWith(gateway(), refusing);

        assertThatThrownBy(() -> m.startRun(run(Strategy.ROUND_ROBIN, 6, null)))
                .isInstanceOf(RejectedExecutionException.class);
        assertThatThrownBy(() -> m.startComparison(new CompareCommand(6, 5, 2)))
                .isInstanceOf(RejectedExecutionException.class);

        assertThat(m.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(m.overview().latestRun()).isNull();
        assertThat(m.overview().latestComparison()).isNull();
        assertThat(events()).isEmpty();
    }

    @Test
    @DisplayName("every rejected request leaves the module IDLE, publishes nothing and changes nothing")
    void validationChangesNothing() {
        cluster.crash(2);
        List<Runnable> rejected = List.of(
                () -> module.startRun(run(null, 6, null)),
                () -> module.startRun(new RunCommand(Strategy.ROUND_ROBIN, 0, 5, 6, null)),
                () -> module.startRun(new RunCommand(Strategy.ROUND_ROBIN, 1001, 5, 6, null)),
                () -> module.startRun(new RunCommand(Strategy.ROUND_ROBIN, 6, 0, 6, null)),
                () -> module.startRun(new RunCommand(Strategy.ROUND_ROBIN, 6, 5001, 6, null)),
                () -> module.startRun(new RunCommand(Strategy.ROUND_ROBIN, 6, 5, 0, null)),
                () -> module.startRun(new RunCommand(Strategy.ROUND_ROBIN, 6, 5, 51, null)),
                () -> module.startRun(new RunCommand(Strategy.ROUND_ROBIN, 1000, 501, 6, null)),
                () -> module.startRun(run(Strategy.ROUND_ROBIN, 60, new CrashPlan(9, 20))),
                () -> module.startRun(run(Strategy.ROUND_ROBIN, 60, new CrashPlan(2, 20))),
                () -> module.startRun(run(Strategy.ROUND_ROBIN, 60, new CrashPlan(3, 0))),
                () -> module.startRun(run(Strategy.ROUND_ROBIN, 60, new CrashPlan(3, 60))),
                () -> module.startRun(run(Strategy.ROUND_ROBIN, 1, new CrashPlan(3, 1))),
                () -> module.startComparison(new CompareCommand(0, 5, 6)),
                () -> module.startComparison(new CompareCommand(300, 400, 6)));
        List<String> outcomes = new ArrayList<>();
        for (Runnable r : rejected) {
            try {
                r.run();
                outcomes.add("accepted");
            } catch (InvalidParameterException e) {
                outcomes.add("400 " + e.parameter());
            } catch (UnknownNodeException e) {
                outcomes.add("404");
            } catch (NodeDownException e) {
                outcomes.add("409 node down");
            }
        }

        assertThat(outcomes).containsExactly("400 strategy", "400 requestCount", "400 requestCount",
                "400 workUnits", "400 workUnits", "400 concurrency", "400 concurrency", "400 totalWork",
                "404", "409 node down", "400 crash.afterServed", "400 crash.afterServed", "400 crash.afterServed",
                "400 requestCount", "400 totalWork");
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(module.overview().latestRun()).isNull();
        assertThat(module.overview().latestComparison()).isNull();
        assertThat(held.tasks).isEmpty();
        assertThat(bus.query(LoadBalancingModule.ID, null, 100)).isEmpty();
    }

    // ------------------------------------------------------------------ crash plan

    @Test
    @DisplayName("a crash injected after 20 of 60 served: the node really crashes, requests are rerouted, none fail while others are healthy")
    void crashPlan() {
        RunDto accepted = module.startRun(run(Strategy.ROUND_ROBIN, 60, new CrashPlan(3, 20)));
        assertThat(accepted.crash().crashed()).isFalse();

        held.runAll();

        RunDto finished = module.overview().latestRun();
        assertThat(finished.state()).isEqualTo(ActionState.FINISHED);
        assertThat(finished.crash().crashed()).isTrue();
        assertThat(finished.report().failures()).isZero();
        assertThat(finished.report().served()).isEqualTo(60);
        assertThat(finished.report().reroutes()).isPositive();
        assertThat(cluster.node(3).status()).isEqualTo(NodeStatus.CRASHED);   // left down (R10)
        assertThat(row(module.overview(), 3).healthy()).isFalse();
        assertThat(events("CRASH_INJECTED")).singleElement().satisfies(e -> {
            assertThat(e.peerId()).isEqualTo(3);
            assertThat(e.data()).containsEntry("runId", accepted.runId()).containsEntry("afterServed", 20);
        });
        assertThat(bus.query("cluster", 3, 100)).anyMatch(e -> e.type().equals("NODE_CRASHED"));
    }

    @Test
    @DisplayName("if the node is already down when its turn comes, crashed is false, CRASH_SKIPPED is published and the run completes")
    void crashSkipped() {
        LoadBalancingGateway crashesFirst = new LoadBalancingGateway(cluster, MULTITHREADING, meters, bus, PROPERTIES) {
            @Override
            public PhaseReport run(Strategy s, int n, int w, int c, String runId, Consumer<DispatchResult> onResult) {
                cluster.crash(3);   // someone else crashes it after the run was accepted
                return super.run(s, n, w, c, runId, onResult);
            }
        };
        LoadBalancingModule m = moduleWith(crashesFirst, held);

        m.startRun(run(Strategy.ROUND_ROBIN, 30, new CrashPlan(3, 10)));
        held.runAll();

        RunDto finished = m.overview().latestRun();
        assertThat(finished.state()).isEqualTo(ActionState.FINISHED);
        assertThat(finished.crash().crashed()).isFalse();
        assertThat(finished.report().failures()).isZero();
        assertThat(events("CRASH_SKIPPED")).hasSize(1);
        assertThat(events("CRASH_INJECTED")).isEmpty();
    }

    // ------------------------------------------------------------------ comparison

    @Test
    @DisplayName("compare all four: an unreported warm-up, four phases in Strategy order, and a finding that matches the measured phases")
    void comparison() {
        ComparisonDto accepted = module.startComparison(new CompareCommand(60, 5, 6));
        assertThat(accepted.state()).isEqualTo(ActionState.RUNNING);
        assertThat(accepted.warmUpRequests()).isEqualTo(60);

        held.runAll();

        ComparisonDto done = module.overview().latestComparison();
        assertThat(done.state()).isEqualTo(ActionState.FINISHED);
        assertThat(done.phases()).extracting(PhaseReportDto::strategy).containsExactly(Strategy.values());
        assertThat(done.phases()).allSatisfy(p -> assertThat(p.failures()).isZero());
        assertThat(done.phases().get(0).nodes()).extracting(n -> n.requests()).containsExactly(20, 20, 20);

        // The finding is computed on unrounded makespans; the DTO shows them rounded to 0.01 ms,
        // so compare with <= and >= here. Who wins is never asserted.
        double rr = done.phases().get(0).makespanMillis();
        List<Double> others = done.phases().subList(1, 4).stream().map(PhaseReportDto::makespanMillis).toList();
        if (done.finding().roundRobinFinishedLast()) {
            assertThat(others).allMatch(o -> o <= rr);
        } else {
            assertThat(others).anyMatch(o -> o >= rr);
        }
        double fastest = makespanOf(done, done.finding().fastest());
        assertThat(done.phases()).allSatisfy(p -> assertThat(p.makespanMillis()).isGreaterThanOrEqualTo(fastest));

        assertThat(events("COMPARISON_STARTED")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("comparisonId", accepted.comparisonId()));
        assertThat(events("RUN_STARTED")).hasSize(4)
                .allSatisfy(e -> assertThat(e.data()).containsEntry("comparisonId", accepted.comparisonId())
                        .containsEntry("kind", "COMPARISON"));
        assertThat(events("RUN_FINISHED")).hasSize(4);
        assertThat(events("COMPARISON_FINISHED")).hasSize(1);
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
    }

    private static double makespanOf(ComparisonDto c, Strategy s) {
        return c.phases().stream().filter(p -> p.strategy() == s).findFirst().orElseThrow().makespanMillis();
    }

    // ------------------------------------------------------------------ failure, metrics, reset

    @Test
    @DisplayName("a run that fails part-way: FAILED with the error, RUN_FAILED, IDLE, and its counts added to the metrics once")
    void failedRun() {
        LoadBalancingGateway breaksAfterTen = new LoadBalancingGateway(cluster, MULTITHREADING, meters, bus, PROPERTIES) {
            @Override
            public PhaseReport run(Strategy s, int n, int w, int c, String runId, Consumer<DispatchResult> onResult) {
                AtomicInteger seen = new AtomicInteger();
                return super.run(s, n, w, c, runId, result -> {
                    if (seen.incrementAndGet() == 10) {
                        throw new IllegalStateException("broke after ten");
                    }
                });
            }
        };
        LoadBalancingModule m = moduleWith(breaksAfterTen, held);

        RunDto accepted = m.startRun(new RunCommand(Strategy.ROUND_ROBIN, 200, 5, 1, null));
        held.runAll();

        RunDto failed = m.overview().latestRun();
        assertThat(failed.state()).isEqualTo(ActionState.FAILED);
        assertThat(failed.error()).isEqualTo("broke after ten");
        assertThat(failed.report()).isNull();
        assertThat(m.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(events("RUN_FAILED")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("runId", accepted.runId()));

        double served = Stream.of(1, 2, 3).mapToDouble(id -> meters.get(MetricNames.BALANCER_DISPATCHES_TOTAL)
                .tag(MetricNames.NODE_ID, String.valueOf(id)).tag("outcome", "served").counter().count()).sum();
        int completed = m.overview().workers().stream().mapToInt(WorkerDto::completed).sum();
        assertThat(completed).isEqualTo(10);
        assertThat(served).isEqualTo(10d);   // added once, not twice
        assertThat(meters.find(MetricNames.BALANCER_MAKESPAN).timers()).isEmpty();
    }

    @Test
    @DisplayName("a finished run is added to the metrics once: served counters equal the workers' completed counts")
    void metricsOncePerRun() {
        module.startRun(run(Strategy.LEAST_CONNECTIONS, 30, null));
        held.runAll();
        module.overview();   // reading never records

        double served = Stream.of(1, 2, 3).mapToDouble(id -> meters.get(MetricNames.BALANCER_DISPATCHES_TOTAL)
                .tag(MetricNames.NODE_ID, String.valueOf(id)).tag("outcome", "served").counter().count()).sum();
        assertThat(served).isEqualTo(30d);
        assertThat(meters.get(MetricNames.BALANCER_MAKESPAN).timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("reset forgets the latest run and comparison and zeroes the worker counters")
    void reset() {
        module.startRun(run(Strategy.ROUND_ROBIN, 6, null));
        held.runAll();
        module.startComparison(new CompareCommand(6, 5, 2));
        held.runAll();
        assertThat(module.overview().latestRun()).isNotNull();

        module.reset();

        LoadBalancingOverviewDto overview = module.overview();
        assertThat(overview.latestRun()).isNull();
        assertThat(overview.latestComparison()).isNull();
        assertThat(overview.workers()).allSatisfy(w -> assertThat(w.completed()).isZero());
    }
}
