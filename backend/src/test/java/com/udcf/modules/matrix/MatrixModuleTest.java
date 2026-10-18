package com.udcf.modules.matrix;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.LeaderElector;
import com.udcf.core.cluster.RoleProvider;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.cluster.UnknownNodeException;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.metrics.MetricNames;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.matrix.InMemoryMatrixNodes.Step;
import com.udcf.modules.matrix.dto.MatrixNodeDto;
import com.udcf.modules.matrix.dto.MatrixOverviewDto;
import com.udcf.modules.matrix.dto.MatrixRunCommand;
import com.udcf.modules.matrix.dto.MatrixRunDto;
import com.udcf.modules.matrix.dto.MatrixSeriesDto;
import com.udcf.modules.mpi.CollectiveOutcome;
import com.udcf.modules.mpi.MpiModule;
import com.udcf.modules.mpi.RmiCollectiveTransport;
import com.udcf.modules.mpi.dto.RootSource;
import com.udcf.modules.rmi.InvalidCallException;
import com.udcf.modules.rmi.PayloadSizer;
import com.udcf.modules.rmi.RmiModule;
import com.udcf.modules.rmi.RmiTestSupport;
import com.udcf.modules.rmi.dto.RmiNodeDto;
import com.udcf.modules.rmi.dto.RmiNodeState;
import com.udcf.modules.rmi.dto.RmiOverviewDto;
import com.udcf.web.NodeStateConflictException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Experiment 10 module with no network: a Mockito RmiModule (so ensureStarted binds nothing), E10b's
 * InMemoryMatrixNodes as both transports and as every node's executor, a recording prepare step, scripted time
 * and a fixed processor count. Outcomes and exact equality, the root rule, validation, busy rejection, run ids
 * and reset, the history bound, the prepare step, the overview's nulls, events, the series and the pool.
 *
 * <p>No socket and no port: the cluster's bases (RMI 28650, clock 28600, election 28610, replication 28620,
 * requests 28640, mapreduce 28630) are numbers only, never bound, because no service is started.</p>
 */
class MatrixModuleTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private static final String DISPATCH = "udcf-matrix-dispatch-";

    private ClusterEventBus bus;
    private Cluster cluster;
    private RmiModule rmi;
    private SimpleMeterRegistry registry;
    private InMemoryMatrixNodes nodes;
    private List<String> steps;
    private RoleProvider roles;
    private MatrixModule module;

    @BeforeEach
    void setUp() {
        bus = RmiTestSupport.bus();
        cluster = new Cluster(new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
                new ClusterProperties.Ports(28650, 28600, 28610, 28620, 28640, 28630)), bus);
        rmi = mock(RmiModule.class);
        when(rmi.overview()).thenAnswer(invocation -> rmiOverview());
        steps = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> steps.add("ensureStarted")).when(rmi).ensureStarted();
        registry = new SimpleMeterRegistry();
        nodes = MatrixTestResults.nodes();
        roles = new RoleProvider(cluster, (LeaderElector) null);   // no election module: no leader means 409 at once
        cluster.assignLeader(1);
        module = newModule(20, node -> steps.add("prepare " + node.id()), MatrixTestResults.stepping());
    }

    @AfterEach
    void tearDown() {
        module.close();
        roles.close();
        cluster.close();
        bus.close();
    }

    private MatrixModule newModule(int historySize, Consumer<com.udcf.core.cluster.ClusterNode> prepare, LongSupplier time) {
        return new MatrixModule(cluster, bus, MatrixTestResults.MATRIX, MatrixTestResults.RUN, new MatrixModuleProperties(historySize),
                MatrixTestResults.MPI, rmi, RmiTestSupport.PROPERTIES, RmiTestSupport.TRANSPORT, registry,
                Clock.fixed(NOW, ZoneOffset.UTC), roles,
                new MatrixModule.Seams(nodes, nodes, node -> nodes.executor(node.id()), prepare, time, () -> 8));
    }

    /** What Experiment 1 reports: no node started. */
    private RmiOverviewDto rmiOverview() {
        List<RmiNodeDto> rmiNodes = cluster.nodes().stream().map(node -> new RmiNodeDto(node.id(), node.status(),
                node.isUp() ? RmiNodeState.NOT_STARTED : RmiNodeState.CRASHED, node.ports().rmi(), null)).toList();
        return new RmiOverviewDto(ModuleStatus.IDLE, null, "NodeEndpoint", rmiNodes, List.of(), null, null, null, null, null,
                List.of(), null);
    }

    private static MatrixRunCommand size(int n) {
        return new MatrixRunCommand(n, null, null);
    }

    private List<ClusterEvent> events(String type) {
        return bus.query(MatrixModule.ID, null, 500).stream().filter(e -> e.type().equals(type)).toList();
    }

    private static <T extends Throwable> T thrown(Class<T> type, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        T caught = catchThrowableOfType(call, type);
        assertThat(caught).as("expected %s", type.getSimpleName()).isNotNull();
        return caught;
    }

    private static long dispatchThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                .filter(t -> t.getName().startsWith(DISPATCH)).count();
    }

    // ------------------------------------------------------------------ outcomes and the root rule

    @Test
    @DisplayName("COMPLETE: the parallel product equals the sequential one bit for bit; run id 1; the seed defaults to the run id")
    void completeIsExact() {
        MatrixRunDto dto = module.run(size(30));

        assertThat(dto.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(dto.runId()).isEqualTo(1);
        assertThat(dto.seed()).isEqualTo(1);
        assertThat(dto.startedAt()).isEqualTo(NOW);
        assertThat(dto.rootNodeId()).isEqualTo(1);
        assertThat(dto.rootSource()).isEqualTo(RootSource.LEADER);
        assertThat(dto.rankNodeIds()).containsExactly(1, 2, 3);
        assertThat(dto.verification().passed()).isTrue();
        assertThat(dto.verification().mismatchedElements()).isZero();
        assertThat(dto.verification().elementsCompared()).isEqualTo(900);
        assertThat(dto.verification().checksumsMatch()).isTrue();
        assertThat(dto.verification().parallelChecksum()).isEqualTo(dto.verification().sequentialChecksum());
        assertThat(dto.timing().derived().speedup()).isNotNull();
        assertThat(module.history()).containsExactly(dto);
        assertThat(module.latest()).isEqualTo(dto);
        assertThat(module.run(1L)).isEqualTo(dto);
        assertThat(registry.find(MetricNames.MATRIX_RUNS_TOTAL).tags("outcome", "COMPLETE").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("an explicit seed is kept; a requested root is used (REQUESTED) with its rank")
    void seedAndRequestedRoot() {
        MatrixRunDto dto = module.run(new MatrixRunCommand(12, 42L, 2));

        assertThat(dto.seed()).isEqualTo(42);
        assertThat(dto.rootNodeId()).isEqualTo(2);
        assertThat(dto.rootSource()).isEqualTo(RootSource.REQUESTED);
        assertThat(dto.rootRank()).isEqualTo(1);
        assertThat(dto.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
    }

    @Test
    @DisplayName("default root: the leader (LEADER); after it crashes and node 2 is elected, node 2, the crashed node skipped")
    void defaultRoot() {
        cluster.assignLeader(3);
        MatrixRunDto led = module.run(size(12));
        assertThat(led.rootNodeId()).isEqualTo(3);
        assertThat(led.rootSource()).isEqualTo(RootSource.LEADER);
        assertThat(led.rootRank()).isEqualTo(2);

        cluster.crash(3);
        cluster.assignLeader(2);
        MatrixRunDto next = module.run(size(12));
        assertThat(next.rootNodeId()).isEqualTo(2);
        assertThat(next.rootSource()).isEqualTo(RootSource.LEADER);
        assertThat(next.skippedNodeIds()).containsExactly(3);
        assertThat(next.rankNodeIds()).containsExactly(1, 2);
        assertThat(next.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
    }

    @Test
    @DisplayName("no leader could be elected: NoLeaderException (409 NO_LEADER) before the guard; no run id, nothing started")
    void noLeader() {
        cluster.assignLeader(null);

        assertThatThrownBy(() -> module.run(size(12))).isInstanceOf(com.udcf.modules.mpi.NoLeaderException.class)
                .hasMessageStartingWith("No leader could be elected, so a matrix run");
        assertThat(steps).isEmpty();
        assertThat(module.history()).isEmpty();
        assertThat(module.overview().defaultRootNodeId()).isNull();
        assertThat(module.overview().defaultRootSource()).isNull();
        cluster.assignLeader(1);
        assertThat(module.run(size(12)).runId()).isEqualTo(1);   // the refused run reserved no id
    }

    @Test
    @DisplayName("an unknown root is 404 and a crashed requested root 409 (Node down); neither reserves a run id")
    void requestedRootErrors() {
        assertThatThrownBy(() -> module.run(new MatrixRunCommand(12, null, 9))).isInstanceOf(UnknownNodeException.class);
        cluster.crash(2);
        assertThat(thrown(NodeDownException.class, () -> module.run(new MatrixRunCommand(12, null, 2))).nodeId())
                .isEqualTo(2);

        assertThat(module.history()).isEmpty();
        assertThat(module.status()).isNotEqualTo(ModuleStatus.BUSY);
        assertThat(module.run(size(12)).runId()).isEqualTo(1);
    }

    @Test
    @DisplayName("every node crashed and no root requested: 409 Node state conflict, node 0")
    void noLiveNode() {
        cluster.crash(1);
        cluster.crash(2);
        cluster.crash(3);

        assertThat(thrown(NodeStateConflictException.class, () -> module.run(size(12))).nodeId()).isZero();
        assertThat(module.history()).isEmpty();
    }

    @Test
    @DisplayName("REFUSED (size below the live nodes): kept in the history, STARTED then REFUSED published, counted, nothing sent")
    void refused() {
        MatrixRunDto dto = module.run(size(2));

        assertThat(dto.outcome()).isEqualTo(CollectiveOutcome.REFUSED);
        assertThat(dto.refusal().reason()).isEqualTo(MatrixRefusal.Reason.SIZE_BELOW_PARTICIPANTS);
        assertThat(dto.timing()).isNull();
        assertThat(module.history()).containsExactly(dto);
        assertThat(nodes.calls()).isEmpty();
        assertThat(events("MATRIX_RUN_STARTED")).hasSize(1);
        assertThat(events("MATRIX_RUN_REFUSED")).singleElement().satisfies(e -> {
            assertThat(e.nodeId()).isEqualTo(1);
            assertThat(e.data()).containsEntry("runId", 1L).containsEntry("size", 2)
                    .containsEntry("reason", "SIZE_BELOW_PARTICIPANTS");
        });
        assertThat(events("MATRIX_RUN_FINISHED")).isEmpty();
        assertThat(registry.find(MetricNames.MATRIX_RUNS_TOTAL).tags("outcome", "REFUSED").counter().count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ validation and busy

    @Test
    @DisplayName("400: every field at once; size range is min-size to the derived maximum; seed 0 to 2^53 - 1; nothing reserved")
    void validation() {
        assertThat(thrown(InvalidCallException.class, () -> module.run(new MatrixRunCommand(null, -1L, null))).errors())
                .containsExactly(Map.entry("size", "size is required"),
                        Map.entry("seed", "seed must be 0-9007199254740991, was -1"));
        assertThat(thrown(InvalidCallException.class, () -> module.run(size(1))).errors())
                .containsExactly(Map.entry("size", "size must be 2-316, was 1"));
        assertThat(thrown(InvalidCallException.class, () -> module.run(size(317))).errors())
                .containsExactly(Map.entry("size", "size must be 2-316, was 317"));
        assertThat(thrown(InvalidCallException.class, () -> module.run(new MatrixRunCommand(12, MpiModule.MAX_SEED + 1, null)))
                .errors()).containsExactly(Map.entry("seed", "seed must be 0-9007199254740991, was 9007199254740992"));

        assertThat(module.run(new MatrixRunCommand(12, MpiModule.MAX_SEED, null)).runId()).isEqualTo(1);
        verify(rmi).ensureStarted();
    }

    @Test
    @DisplayName("busy: start and run answer 409 while the guard is held, publish ACTION_REJECTED_BUSY, change nothing")
    void busy() {
        ModuleActionGuard.ActionTicket held = module.guard().begin("Matrix run of size 316");
        try {
            assertThat(module.status()).isEqualTo(ModuleStatus.BUSY);
            assertThat(module.overview().actionInProgress()).isEqualTo("Matrix run of size 316");
            assertThat(thrown(ModuleBusyException.class, () -> module.run(size(12))).actionInProgress())
                    .isEqualTo("Matrix run of size 316");
            assertThatThrownBy(module::start).isInstanceOf(ModuleBusyException.class);
        } finally {
            held.close();
        }
        assertThat(events("ACTION_REJECTED_BUSY")).hasSize(2).allSatisfy(e -> {
            assertThat(e.nodeId()).isZero();
            assertThat(e.data()).containsEntry("actionInProgress", "Matrix run of size 316");
        });
        assertThat(events("ACTION_REJECTED_BUSY").get(0).data()).containsEntry("requested", "Matrix run of size 12");
        assertThat(module.history()).isEmpty();
        verify(rmi, never()).ensureStarted();
        assertThat(steps).isEmpty();
    }

    // ------------------------------------------------------------------ run ids, reset, history

    @Test
    @DisplayName("run ids count from 1; reset forgets the history and restarts them at 1; latest and unknown ids are 404")
    void runIdsAndReset() {
        assertThat(thrown(UnknownRunException.class, module::latest).runId()).isNull();
        module.run(size(12));
        module.run(size(12));
        assertThat(module.history()).extracting(MatrixRunDto::runId).containsExactly(2L, 1L);

        module.reset();

        assertThat(module.history()).isEmpty();
        assertThatThrownBy(module::latest).isInstanceOf(UnknownRunException.class);
        assertThat(thrown(UnknownRunException.class, () -> module.run(2L)).runId()).isEqualTo(2);
        assertThat(module.run(size(12)).runId()).isEqualTo(1);
    }

    @Test
    @DisplayName("a run still going when the module resets is answered but not recorded; the next run gets id 1")
    void resetDuringARun() {
        nodes.before(Step.COMPUTE, 2, () -> module.reset());

        MatrixRunDto answered = module.run(size(12));

        assertThat(answered.runId()).isEqualTo(1);
        assertThat(module.history()).isEmpty();
        assertThat(events("MATRIX_RUN_FINISHED")).hasSize(1);   // it really happened
    }

    @Test
    @DisplayName("the history keeps history-size runs, newest first; a history smaller than the series is refused at startup")
    void historyBound() {
        module.close();
        module = newModule(4, node -> { }, MatrixTestResults.stepping());
        for (int i = 0; i < 5; i++) {
            module.run(size(12));
        }
        assertThat(module.history()).extracting(MatrixRunDto::runId).containsExactly(5L, 4L, 3L, 2L);
        assertThatThrownBy(() -> module.run(1L)).isInstanceOf(UnknownRunException.class);

        assertThatThrownBy(() -> newModule(3, node -> { }, MatrixTestResults.stepping()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("udcf.matrix.history-size 3 is below the 4 series sizes [50, 100, 200, 316]");
    }

    @Test
    @DisplayName("an unexpected failure (time going backwards) releases the guard and gives the run id back")
    void unexpectedFailure() {
        module.close();
        AtomicLong now = new AtomicLong(1_000_000);
        module = newModule(20, node -> { }, () -> now.addAndGet(-1_000));

        assertThatThrownBy(() -> module.run(size(12))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("went backwards");

        assertThat(module.status()).isNotEqualTo(ModuleStatus.BUSY);
        assertThat(module.history()).isEmpty();
        assertThat(events("MATRIX_RUN_STARTED")).singleElement()
                .satisfies(e -> assertThat(e.data()).containsEntry("runId", 1L));
    }

    // ------------------------------------------------------------------ prepare, start, pool

    @Test
    @DisplayName("a run starts RMI, then prepares every live node's request listener, then sends; a crashed node is not prepared")
    void prepareBeforeTheRun() {
        cluster.crash(3);
        nodes.before(Step.DELIVER, 2, () -> steps.add("deliver 2"));

        module.run(size(12));

        assertThat(steps).containsExactly("ensureStarted", "prepare 1", "prepare 2", "deliver 2", "deliver 2");
    }

    @Test
    @DisplayName("a prepare step that throws is swallowed: start and run go on (a failed prepare becomes a hole later)")
    void prepareNeverThrows() {
        module.close();
        module = newModule(20, node -> {
            if (node.id() == 2) {
                throw new IllegalStateException("Service 'requests' failed to start on node 2");
            }
        }, MatrixTestResults.stepping());

        assertThat(module.start().status()).isNotEqualTo(ModuleStatus.BUSY);
        assertThat(module.run(size(12)).outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
    }

    @Test
    @DisplayName("start: RMI, the pool (created lazily, one thread per node) and every live listener; close ends the threads")
    void startAndPool() {
        long before = dispatchThreads();

        module.start();
        assertThat(dispatchThreads()).isEqualTo(before + 3);
        assertThat(steps).containsExactly("ensureStarted", "prepare 1", "prepare 2", "prepare 3");
        module.start();   // idempotent: no second pool
        assertThat(dispatchThreads()).isEqualTo(before + 3);

        module.close();
        module.close();
        await().atMost(5, TimeUnit.SECONDS).until(() -> dispatchThreads() == before);
    }

    // ------------------------------------------------------------------ reads

    @Test
    @DisplayName("overview before start: IDLE, nulls for what is unknown, the limits and budget, every text")
    void overviewBeforeStart() {
        MatrixOverviewDto overview = module.overview();

        assertThat(overview.status()).isEqualTo(ModuleStatus.IDLE);
        assertThat(overview.actionInProgress()).isNull();
        assertThat(overview.boundName()).isEqualTo(MatrixEndpoint.BOUND_NAME);
        assertThat(overview.participants()).extracting(MatrixNodeDto::nodeId).containsExactly(1, 2, 3);
        for (MatrixNodeDto node : overview.participants()) {
            assertThat(node.matrixEndpointExported()).isFalse();
            assertThat(node.keptRunIds()).isNull();
            assertThat(node.storedCollectiveIds()).isNull();
            assertThat(node.requestListenerRunning()).isFalse();
            assertThat(node.requestsPort()).isEqualTo(28640 + node.nodeId());
            assertThat(node.rmiState()).isEqualTo(RmiNodeState.NOT_STARTED);
        }
        assertThat(overview.defaultRootNodeId()).isEqualTo(1);
        assertThat(overview.defaultRootSource()).isEqualTo(RootSource.LEADER);
        assertThat(overview.minimumSizeNow()).isEqualTo(3);
        assertThat(overview.processors()).isEqualTo(8);
        assertThat(overview.speedupCeiling()).isEqualTo(3);
        assertThat(overview.speedupBasis()).isEqualTo(MatrixTexts.speedupBasis(MatrixTestResults.BASIS, 3, 1));
        assertThat(overview.limits().maxSize()).isEqualTo(316);
        assertThat(overview.limits().minSize()).isEqualTo(2);
        assertThat(overview.limits().historySize()).isEqualTo(20);
        assertThat(overview.limits().maxSeed()).isEqualTo(MpiModule.MAX_SEED);
        assertThat(overview.limits().worstCaseRunMillis()).isEqualTo(26_001);
        assertThat(overview.limits().recommendedMinClientTimeoutMillis()).isEqualTo(26_001);
        assertThat(overview.limits().transferWorstMillis()).isEqualTo(12_000);
        assertThat(overview.experimentTwoSideEffect()).contains("(28641 to 28643 here)");
        assertThat(overview.fairnessNote()).isEqualTo(MatrixTexts.fairness(1));
        assertThat(overview.collectiveLatencyBasis()).isEqualTo(RmiCollectiveTransport.LATENCY_BASIS);
        assertThat(overview.payloadSizeBasis()).isEqualTo(PayloadSizer.LABEL);
        assertThat(overview.glossary()).isEqualTo(MatrixTexts.GLOSSARY);
        assertThat(overview.coHosting()).isEqualTo(MatrixTexts.CO_HOSTING);
        assertThat(overview.latest()).isNull();
    }

    @Test
    @DisplayName("overview with every node down: no default root, no minimum size, no ceiling, no basis (null, never 0)")
    void overviewAllDown() {
        cluster.crash(1);
        cluster.crash(2);
        cluster.crash(3);

        MatrixOverviewDto overview = module.overview();
        assertThat(overview.defaultRootNodeId()).isNull();
        assertThat(overview.defaultRootSource()).isNull();
        assertThat(overview.minimumSizeNow()).isNull();
        assertThat(overview.speedupCeiling()).isNull();
        assertThat(overview.speedupBasis()).isNull();
        assertThat(overview.processors()).isEqualTo(8);
    }

    @Test
    @DisplayName("events: STARTED and FINISHED from the root with their data; no derived seed in any event")
    void events() {
        MatrixRunDto dto = module.run(new MatrixRunCommand(12, 42L, null));

        assertThat(events("MATRIX_RUN_STARTED")).singleElement().satisfies(e -> {
            assertThat(e.nodeId()).isEqualTo(1);
            assertThat(e.data()).containsEntry("runId", 1L).containsEntry("size", 12).containsEntry("seed", 42L)
                    .containsEntry("rootSource", "LEADER").containsEntry("liveNodeIds", List.of(1, 2, 3));
            assertThat(e.message()).isEqualTo("Node 1 started matrix run 1 of size 12 over the live nodes [1, 2, 3] "
                    + "(root: the leader elected by Experiment 4)");
        });
        assertThat(events("MATRIX_RUN_FINISHED")).singleElement().satisfies(e -> {
            assertThat(e.nodeId()).isEqualTo(1);
            assertThat(e.data()).containsEntry("outcome", "COMPLETE").containsEntry("computesDone", 3L)
                    .containsEntry("computesFailed", 0L).containsEntry("computesNotAttempted", 0L)
                    .containsEntry("parallelTotalNanos", dto.timing().measured().parallelTotalNanos())
                    .containsEntry("speedup", dto.timing().derived().speedup()).containsEntry("passed", true)
                    .doesNotContainKey("latencyMillis");
            assertThat(e.message()).startsWith("Node 1 finished matrix run 1 of size 12: complete, 3 of 3 blocks computed in ");
        });
        String all = bus.query(MatrixModule.ID, null, 500).toString();
        MatrixJsonChecks.assertNoDerivedSeed(all, List.of(42L));
    }

    @Test
    @DisplayName("series through the module: three sizes run gives done, the fourth size a gap")
    void series() {
        module.run(size(50));
        module.run(size(100));
        module.run(size(200));

        MatrixSeriesDto series = module.series();
        assertThat(series.points()).extracting(MatrixSeriesDto.Point::size).containsExactly(50, 100, 200);
        assertThat(series.points()).allSatisfy(p -> assertThat(p.speedup()).isNotNull());
        assertThat(series.sizesWithSpeedup()).isEqualTo(3);
        assertThat(series.done()).isTrue();
        assertThat(series.gaps()).extracting(MatrixSeriesDto.Gap::size).containsExactly(316);
    }
}
