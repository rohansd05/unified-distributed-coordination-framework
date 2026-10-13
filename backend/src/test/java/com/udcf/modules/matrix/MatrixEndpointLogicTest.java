package com.udcf.modules.matrix;

import com.udcf.core.clock.LamportClock;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.modules.mpi.CollectiveEndpointLogic;
import com.udcf.modules.mpi.MpiProperties;
import com.udcf.modules.mpi.VectorChecksum;
import com.udcf.modules.rmi.CallContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A node's matrix logic with a real {@link CollectiveEndpointLogic} as its input store and no sockets:
 * compute, keep and fetch; every refusal reason; Lamport; retention.
 */
class MatrixEndpointLogicTest {

    private static final MpiProperties MPI = new MpiProperties(100_000, 10_000, 20, 8, 300);
    private static final MatrixRunProperties RUN = new MatrixRunProperties(1, 2000, 2);
    private static final int N = 10;
    private static final long SEED = 42;
    private static final long SCATTER = 11;
    private static final long BROADCAST = 12;
    private static final int RANK = 1;
    private static final long STEP = 700;
    private static final double[] A = MatrixGenerator.a(SEED, N);
    private static final double[] B = MatrixGenerator.b(SEED, N);
    private static final RowPartition.RowBlock ROWS = RowPartition.of(N, 3).get(RANK);   // rows 4 to 6
    private static final double[] A_ROWS = RowPartition.rows(A, ROWS);

    private final LamportClock clock = new LamportClock();
    private final CollectiveEndpointLogic store = new CollectiveEndpointLogic(2, clock, MPI);
    private final AtomicLong nodeTime = new AtomicLong();

    private MatrixEndpointLogic logic(NodeExecutor executor) {
        return logic(executor, RUN);
    }

    private MatrixEndpointLogic logic(NodeExecutor executor, MatrixRunProperties run) {
        return new MatrixEndpointLogic(2, clock, () -> Optional.of(store), executor, run, () -> nodeTime.getAndAdd(STEP));
    }

    private void stored() {
        store.receive(new CallContext(1, 0), SCATTER, RANK, A_ROWS);
        store.receive(new CallContext(1, 0), BROADCAST, RANK, B);
    }

    /** The node's refusal thrown by {@code call}. */
    private static MatrixComputeException refusal(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        return catchThrowableOfType(call, MatrixComputeException.class);
    }

    private static CallContext from(long lamport) {
        return new CallContext(1, lamport);
    }

    private MatrixComputeException refusedCompute(MatrixEndpointLogic logic, int rank, int n) {
        return refusal(() -> logic.computeBlock(from(0), 1, rank, n, SCATTER, BROADCAST));
    }

    @Test
    @DisplayName("compute multiplies the stored rows by the stored B, keeps the block and acknowledges it; fetch returns it bit for bit")
    void computeKeepFetch() throws Exception {
        stored();
        MatrixEndpointLogic logic = logic(InMemoryMatrixNodes.DIRECT);

        ComputeAck ack = logic.computeBlock(from(0), 1, RANK, N, SCATTER, BROADCAST);
        double[] expected = MatrixMultiply.multiplyRows(A_ROWS, B, N);
        assertThat(ack.nodeId()).isEqualTo(2);
        assertThat(ack.runId()).isEqualTo(1);
        assertThat(ack.rank()).isEqualTo(RANK);
        assertThat(ack.rowCount()).isEqualTo(3);
        assertThat(ack.computeNanos()).isEqualTo(STEP);
        assertThat(ack.checksum()).isEqualTo(VectorChecksum.of(expected));
        assertThat(logic.keptRunIds()).containsExactly(1L);

        BlockReply reply = logic.fetchBlock(from(0), 1, RANK);
        assertThat(Arrays.equals(reply.values(), expected)).isTrue();
        assertThat(reply.nodeId()).isEqualTo(2);
        assertThat(reply.rank()).isEqualTo(RANK);
    }

    @Test
    @DisplayName("Lamport: update on receipt, tick for the reply, on compute and on fetch")
    void lamport() throws Exception {
        stored();
        long before = clock.current();
        MatrixEndpointLogic logic = logic(InMemoryMatrixNodes.DIRECT);

        assertThat(logic.computeBlock(from(before + 50), 1, RANK, N, SCATTER, BROADCAST).lamportTime()).isEqualTo(before + 52);
        assertThat(logic.fetchBlock(from(0), 1, RANK).lamportTime()).isEqualTo(before + 54);
        assertThat(clock.current()).isEqualTo(before + 54);
    }

    @Test
    @DisplayName("invalid arguments are refused before anything changes: no clock update, nothing kept")
    void invalidArgumentsChangeNothing() {
        stored();
        long before = clock.current();
        MatrixEndpointLogic logic = logic(InMemoryMatrixNodes.DIRECT);

        assertThatThrownBy(() -> logic.computeBlock(null, 1, RANK, N, SCATTER, BROADCAST)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> logic.computeBlock(from(5), 0, RANK, N, SCATTER, BROADCAST)).hasMessage("runId must be >= 1, was 0");
        assertThatThrownBy(() -> logic.computeBlock(from(5), 1, -1, N, SCATTER, BROADCAST)).hasMessage("rank must be >= 0, was -1");
        assertThatThrownBy(() -> logic.computeBlock(from(5), 1, RANK, 0, SCATTER, BROADCAST)).hasMessage("n must be >= 1, was 0");
        assertThatThrownBy(() -> logic.computeBlock(from(5), 1, RANK, N, 0, BROADCAST))
                .hasMessage("collective ids must be >= 1, were 0 and 12");
        assertThatThrownBy(() -> logic.fetchBlock(from(5), 0, RANK)).hasMessage("runId must be >= 1, was 0");
        assertThat(clock.current()).isEqualTo(before);
        assertThat(logic.keptRunIds()).isEmpty();
    }

    @Test
    @DisplayName("INPUT_MISSING: no collective endpoint, A never arrived, B never arrived, A dropped by the store's limit; the clock took the receipt only")
    void inputMissing() {
        MatrixEndpointLogic noEndpoint = new MatrixEndpointLogic(2, clock, Optional::empty, InMemoryMatrixNodes.DIRECT, RUN, () -> 0);
        assertThat(refusedCompute(noEndpoint, RANK, N).reason()).isEqualTo(MatrixComputeException.Reason.INPUT_MISSING);

        MatrixEndpointLogic logic = logic(InMemoryMatrixNodes.DIRECT);
        store.receive(from(0), BROADCAST, RANK, B);
        long before = clock.current();
        MatrixComputeException noA = refusedCompute(logic, RANK, N);
        assertThat(noA.reason()).isEqualTo(MatrixComputeException.Reason.INPUT_MISSING);
        assertThat(noA.getMessage()).isEqualTo("Collective 11 (its rows of A) is not stored on node 2: it never arrived, or the store's limit dropped it.");
        assertThat(clock.current()).isEqualTo(before + 1);

        CollectiveEndpointLogic onlyA = new CollectiveEndpointLogic(2, new LamportClock(), MPI);
        onlyA.receive(from(0), SCATTER, RANK, A_ROWS);
        MatrixEndpointLogic withoutB = new MatrixEndpointLogic(2, new LamportClock(), () -> Optional.of(onlyA),
                InMemoryMatrixNodes.DIRECT, RUN, () -> 0);
        assertThat(refusedCompute(withoutB, RANK, N).getMessage()).startsWith("Collective 12 (B) is not stored on node 2");

        stored();
        for (long id = 100; id < 100 + MPI.receiveStoreSize(); id++) {
            store.receive(from(0), id, 0, new double[] {1});
        }
        assertThat(refusedCompute(logic, RANK, N).reason()).isEqualTo(MatrixComputeException.Reason.INPUT_MISSING);
        assertThat(logic.keptRunIds()).isEmpty();
    }

    @Test
    @DisplayName("INPUT_MISMATCH: another rank's rows, B that is not n x n, rows of A that are not whole rows")
    void inputMismatch() {
        stored();
        MatrixEndpointLogic logic = logic(InMemoryMatrixNodes.DIRECT);
        MatrixComputeException otherRank = refusedCompute(logic, 0, N);
        assertThat(otherRank.reason()).isEqualTo(MatrixComputeException.Reason.INPUT_MISMATCH);
        assertThat(otherRank.getMessage()).isEqualTo("Collective 11 was sent to rank 1, not rank 0.");
        assertThat(refusedCompute(logic, RANK, 11).getMessage()).isEqualTo("B has 100 values, not 11 x 11.");

        store.receive(from(0), SCATTER, RANK, Arrays.copyOf(A_ROWS, 25));
        assertThat(refusedCompute(logic, RANK, N).getMessage()).isEqualTo("The rows of A have 25 values, not whole rows of 10.");
        assertThat(logic.keptRunIds()).isEmpty();
    }

    @Test
    @DisplayName("retention: at most result-store-size blocks, the oldest dropped (BLOCK_MISSING), a repeated run id becomes the newest")
    void retention() throws Exception {
        stored();
        MatrixEndpointLogic logic = logic(InMemoryMatrixNodes.DIRECT);
        for (long run = 1; run <= 3; run++) {
            logic.computeBlock(from(0), run, RANK, N, SCATTER, BROADCAST);
        }
        assertThat(logic.keptRunIds()).containsExactly(2L, 3L);
        MatrixComputeException dropped = refusal(() -> logic.fetchBlock(from(0), 1, RANK));
        assertThat(dropped.reason()).isEqualTo(MatrixComputeException.Reason.BLOCK_MISSING);
        assertThat(dropped.getMessage()).isEqualTo("Node 2 keeps no block of C for run 1 and rank 1: it was never computed here, "
                + "or the result store's limit dropped it.");

        logic.computeBlock(from(0), 2, RANK, N, SCATTER, BROADCAST);
        assertThat(logic.keptRunIds()).containsExactly(3L, 2L);
        assertThat(refusal(() -> logic.fetchBlock(from(0), 3, 0)).reason())
                .isEqualTo(MatrixComputeException.Reason.BLOCK_MISSING);
    }

    @Test
    @DisplayName("every executor failure becomes the node's reason, and nothing is kept")
    void executorFailures() {
        stored();
        Map<MatrixComputeException.Reason, NodeExecutor> cases = Map.of(
                MatrixComputeException.Reason.EXECUTOR_UNAVAILABLE, task -> {
                    throw new NodeDownException(2);
                },
                MatrixComputeException.Reason.EXECUTOR_REJECTED,
                task -> CompletableFuture.failedFuture(new RejectedExecutionException("full")),
                MatrixComputeException.Reason.COMPUTE_FAILED, task -> CompletableFuture.failedFuture(new ArithmeticException()),
                MatrixComputeException.Reason.COMPUTE_TIMED_OUT, task -> new CompletableFuture<>());
        for (Map.Entry<MatrixComputeException.Reason, NodeExecutor> entry : cases.entrySet()) {
            MatrixEndpointLogic logic = logic(entry.getValue(), new MatrixRunProperties(1, 50, 2));
            assertThat(refusedCompute(logic, RANK, N).reason()).as(entry.getKey().name()).isEqualTo(entry.getKey());
            assertThat(logic.keptRunIds()).isEmpty();
        }
        assertThat(List.of(MatrixComputeException.Reason.values())).hasSize(7);
    }
}
