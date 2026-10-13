package com.udcf.modules.matrix;

import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.events.ClusterEvent;
import com.udcf.modules.mpi.CollectiveOutcome;
import com.udcf.modules.mpi.TransferException;
import com.udcf.modules.mpi.TransferRecord;
import com.udcf.modules.mpi.TransferStatus;
import com.udcf.modules.multithreading.RequestsNodeService;
import com.udcf.modules.multithreading.dto.ThreadPoolStats;
import com.udcf.modules.rmi.CallContext;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.RegistrySnapshot;
import com.udcf.modules.rmi.RmiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Whole parallel runs over real sockets (RMI for the collectives and the matrix endpoint) with every block
 * multiplied on its node's real Experiment 2 executor (link L6 end to end): exact results, a crash between the
 * scatter and the compute, the three registry names, and the side effect on Experiment 2.
 *
 * <p>Test ports (Track C, E10b): RMI base 28725 (28726-28728) and requests base 28735 (28736-28738), both bound;
 * every other base is never bound.</p>
 */
class MatrixEndToEndTest {

    private static final int RMI_BASE = 28725;
    private static final int REQUESTS_BASE = 28735;
    private static final MatrixLimits LIMITS = new MatrixLimits(new MatrixProperties(2, List.of(50, 100, 200, 316)),
            MatrixRmiFixture.MPI);
    private static final MatrixRunBudget BUDGET = MatrixRunBudget.of(RmiTestSupport.PROPERTIES, RmiTestSupport.TRANSPORT,
            MatrixRmiFixture.MPI, MatrixRmiFixture.RUN);

    private MatrixRmiFixture fixture;
    private MatrixDispatchPool pool;
    private ParallelMatrixExecutor executor;
    private long nextRunId = 1;

    @BeforeEach
    void setUp() {
        fixture = new MatrixRmiFixture(RMI_BASE, REQUESTS_BASE, MatrixRmiFixture.RUN, true);
        fixture.startAll();
        pool = new MatrixDispatchPool(3);
        executor = new ParallelMatrixExecutor(LIMITS, MatrixRmiFixture.RUN, MatrixRmiFixture.MPI, BUDGET, pool, System::nanoTime);
    }

    @AfterEach
    void tearDown() {
        pool.close();
        fixture.close();
    }

    private MatrixRunResult run(int n, int root, List<Integer> participants, MatrixTransport matrices) {
        ClusterNode rootNode = fixture.cluster.node(root);
        return executor.run(new MatrixRunRequest(nextRunId++, n, 2026, root, participants, Set.copyOf(participants)),
                rootNode.clock(), fixture.collectives, matrices,
                NodeExecutor.experimentTwo(rootNode, MatrixRmiFixture.MULTITHREADING, fixture.meters, fixture.bus));
    }

    private long completedTasks(int nodeId) {
        return RequestsNodeService.find(fixture.cluster.node(nodeId)).flatMap(RequestsNodeService::snapshot)
                .map(ThreadPoolStats::completedTasks).orElse(-1L);
    }

    @Test
    @DisplayName("COMPLETE and exactly equal to the sequential product for n in {7, 50, 100} and p in {2, 3}, roots 1 and 2")
    void exactOverRealSockets() {
        for (int n : new int[] {7, 50, 100}) {
            for (List<Integer> participants : List.of(List.of(1, 2), List.of(1, 2, 3))) {
                for (int root : List.of(1, 2)) {
                    String label = "n=" + n + " nodes=" + participants + " root=" + root;
                    MatrixRunResult result = run(n, root, participants, fixture.matrices);

                    assertThat(result.outcome()).as(label).isEqualTo(CollectiveOutcome.COMPLETE);
                    assertThat(result.verification().passed()).as(label).isTrue();
                    assertThat(result.verification().mismatchedElements()).as(label).isZero();
                    assertThat(result.verification().checksumsMatch()).as(label).isTrue();
                    assertThat(result.inputsDelivered()).as(label).isTrue();
                    assertThat(result.computes()).as(label).allMatch(c -> c.status() == ComputeRecord.Status.DONE);
                    assertThat(result.timing().speedup()).as(label).isNotNull();
                    assertThat(result.timing().computeBoundSpeedup()).as(label).isNotNull();
                }
            }
        }
    }

    @Test
    @DisplayName("each block runs on its node's Experiment 2 executor: one more completed task per node, no request history, the service started once")
    void runsOnExperimentTwo() {
        List<Integer> all = List.of(1, 2, 3);
        assertThat(all).allMatch(id -> RequestsNodeService.find(fixture.cluster.node(id)).isEmpty());

        assertThat(run(50, 1, all, fixture.matrices).outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        await().atMost(5, TimeUnit.SECONDS).until(() -> all.stream().allMatch(id -> completedTasks(id) == 1));
        assertThat(run(50, 1, all, fixture.matrices).outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        await().atMost(5, TimeUnit.SECONDS).until(() -> all.stream().allMatch(id -> completedTasks(id) == 2));

        for (int id : all) {
            RequestsNodeService service = RequestsNodeService.find(fixture.cluster.node(id)).orElseThrow();
            assertThat(service.isRunning()).as("node %d", id).isTrue();
            assertThat(service.port()).isEqualTo(REQUESTS_BASE + id);
            assertThat(service.registry().size()).as("node %d", id).isZero();
            List<ClusterEvent> started = fixture.bus.query("cluster", id, 100).stream()
                    .filter(e -> e.type().equals("SERVICE_STARTED") && "requests".equals(e.data().get("service"))).toList();
            assertThat(started).as("node %d", id).hasSize(1);
        }
        assertThat(fixture.bus.query(RequestsNodeService.MODULE, null, 100)).isEmpty();
    }

    @Test
    @DisplayName("node 3 crashes between the scatter and the compute: its compute fails at LOOKUP, its rows are a hole; PARTIAL")
    void crashBetweenScatterAndCompute() {
        MatrixTransport crashFirst = new MatrixTransport() {
            @Override
            public ComputeAck compute(int targetNodeId, CallContext context, long runId, int rank, int n, long scatterId,
                                      long broadcastId) throws TransferException {
                if (targetNodeId == 3) {
                    fixture.cluster.crash(3);
                }
                return fixture.matrices.compute(targetNodeId, context, runId, rank, n, scatterId, broadcastId);
            }

            @Override
            public BlockReply fetch(int targetNodeId, CallContext context, long runId, int rank) throws TransferException {
                return fixture.matrices.fetch(targetNodeId, context, runId, rank);
            }
        };
        MatrixRunResult result = run(50, 1, List.of(1, 2, 3), crashFirst);

        assertThat(result.scatter().transfers().get(2).status()).isEqualTo(TransferStatus.DELIVERED);
        ComputeRecord compute = result.computes().get(2);
        assertThat(compute.status()).isEqualTo(ComputeRecord.Status.FAILED);
        assertThat(compute.failure().stage()).isEqualTo(CallStage.LOOKUP);
        assertThat(compute.failure().kind()).isIn(RmiTestSupport.NODE_GONE);
        TransferRecord fetch = result.gather().get(2);
        assertThat(fetch.status()).isEqualTo(TransferStatus.NOT_ATTEMPTED);
        assertThat(result.gather().get(1).verified()).isTrue();
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
        assertThat(result.verification().blocksMissing()).isEqualTo(1);
        assertThat(result.verification().passed()).isNull();
        assertThat(result.verification().parallelChecksum()).isNull();
    }

    @Test
    @DisplayName("every node's registry lists three names: CollectiveEndpoint, MatrixEndpoint and NodeEndpoint")
    void registryListsThreeNames() throws Exception {
        for (int id = 1; id <= 3; id++) {
            RegistrySnapshot snapshot = fixture.lookup.snapshot(id);
            assertThat(snapshot.names()).extracting(RegistrySnapshot.BoundName::name)
                    .containsExactlyInAnyOrder("CollectiveEndpoint", "MatrixEndpoint", "NodeEndpoint");
        }
    }
}
