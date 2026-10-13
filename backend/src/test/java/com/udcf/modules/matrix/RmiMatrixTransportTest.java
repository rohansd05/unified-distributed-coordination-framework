package com.udcf.modules.matrix;

import com.udcf.core.cluster.NodeDownException;
import com.udcf.modules.mpi.TransferException;
import com.udcf.modules.mpi.VectorChecksum;
import com.udcf.modules.rmi.CallContext;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.RemoteFailureClassifier;
import com.udcf.modules.rmi.RemoteFailureKind;
import com.udcf.modules.rmi.RmiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * {@link RmiMatrixTransport} and {@link MatrixEndpointLogic} over real RMI sockets with scripted executors:
 * compute and fetch, the LOOKUP failures of a node that was never started, crashed or exports no
 * MatrixEndpoint, and the node's reasons crossing the wire as METHOD_THREW with a {@link MatrixComputeException}.
 *
 * <p>Test ports (Track C, E10b): RMI base 28705 (28706-28708); every other base is never bound.</p>
 */
class RmiMatrixTransportTest {

    private static final int BASE = 28705;
    private static final int N = 10;
    private static final long SEED = 42;
    private static final long SCATTER = 21;
    private static final long BROADCAST = 22;
    private static final double[] A = MatrixGenerator.a(SEED, N);
    private static final double[] B = MatrixGenerator.b(SEED, N);

    private MatrixRmiFixture fixture;

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    private static CallContext from(long lamport) {
        return new CallContext(1, lamport);
    }

    /** Rank {@code rank}'s rows of A (n = 10 over 3 ranks) and B, delivered to node {@code nodeId} over RMI. */
    private double[] put(int nodeId, int rank) throws TransferException {
        double[] rows = RowPartition.rows(A, RowPartition.of(N, 3).get(rank));
        fixture.collectives.deliver(nodeId, from(1), SCATTER, rank, rows);
        fixture.collectives.deliver(nodeId, from(1), BROADCAST, rank, B);
        return rows;
    }

    private TransferException computeFails(int nodeId, int rank) {
        return catchThrowableOfType(() -> fixture.matrices.compute(nodeId, from(1), 1, rank, N, SCATTER, BROADCAST),
                TransferException.class);
    }

    private static RemoteFailureKind kind(TransferException failure) {
        return RemoteFailureClassifier.classify(failure.original(), failure.stage());
    }

    private static MatrixComputeException.Reason reason(TransferException failure) {
        assertThat(failure.stage()).isEqualTo(CallStage.CALL);
        assertThat(kind(failure)).isEqualTo(RemoteFailureKind.METHOD_THREW);
        return ((MatrixComputeException) failure.original()).reason();
    }

    @Test
    @DisplayName("compute then fetch over real sockets: the block is the exact product of the node's rows, the stub a proxy")
    void computeAndFetch() throws Exception {
        fixture = new MatrixRmiFixture(BASE, MatrixRmiFixture.RUN);
        fixture.startAll();
        double[] rows = put(2, 1);

        ComputeAck ack = fixture.matrices.compute(2, from(5), 1, 1, N, SCATTER, BROADCAST);
        BlockReply reply = fixture.matrices.fetch(2, from(6), 1, 1);

        double[] expected = MatrixMultiply.multiplyRows(rows, B, N);
        assertThat(ack.nodeId()).isEqualTo(2);
        assertThat(ack.rowCount()).isEqualTo(3);
        assertThat(ack.checksum()).isEqualTo(VectorChecksum.of(expected));
        assertThat(Arrays.equals(reply.values(), expected)).isTrue();
        assertThat(Proxy.isProxyClass(fixture.lookup.lookup(2, MatrixEndpoint.BOUND_NAME, MatrixEndpoint.class).getClass())).isTrue();
    }

    @Test
    @DisplayName("a node whose RMI was never started fails at LOOKUP with CONNECTION_REFUSED")
    void neverStarted() {
        fixture = new MatrixRmiFixture(BASE, MatrixRmiFixture.RUN);
        fixture.start(1);
        fixture.start(2);

        TransferException failure = computeFails(3, 2);
        assertThat(failure.stage()).isEqualTo(CallStage.LOOKUP);
        assertThat(kind(failure)).isEqualTo(RemoteFailureKind.CONNECTION_REFUSED);
    }

    @Test
    @DisplayName("a crashed node fails at LOOKUP (refused, or lost on a pooled connection); a recovered node has a fresh logic")
    void crashedAndRecovered() throws Exception {
        fixture = new MatrixRmiFixture(BASE, MatrixRmiFixture.RUN);
        fixture.startAll();
        put(3, 2);
        fixture.matrices.compute(3, from(1), 1, 2, N, SCATTER, BROADCAST);
        MatrixEndpointLogic before = fixture.matrix(3);

        fixture.cluster.crash(3);
        TransferException failure = computeFails(3, 2);
        assertThat(failure.stage()).isEqualTo(CallStage.LOOKUP);
        assertThat(kind(failure)).isIn(RmiTestSupport.NODE_GONE);

        RmiTestSupport.drainUntilRefused(() -> fixture.lookup.lookup(3, MatrixEndpoint.BOUND_NAME, MatrixEndpoint.class));
        fixture.cluster.recover(3);
        assertThat(fixture.matrix(3)).isNotSameAs(before);
        assertThat(fixture.matrix(3).keptRunIds()).isEmpty();
        TransferException gone = catchThrowableOfType(() -> fixture.matrices.fetch(3, from(1), 1, 2), TransferException.class);
        assertThat(reason(gone)).isEqualTo(MatrixComputeException.Reason.BLOCK_MISSING);
        assertThat(reason(computeFails(3, 2))).isEqualTo(MatrixComputeException.Reason.INPUT_MISSING);
    }

    @Test
    @DisplayName("a node exporting only Experiments 1 and 9 has no MatrixEndpoint: LOOKUP, NOT_BOUND")
    void notBound() {
        fixture = new MatrixRmiFixture(BASE, MatrixRmiFixture.RUN);
        fixture.start(2, fixture.collectiveExport);

        TransferException failure = computeFails(2, 1);
        assertThat(failure.stage()).isEqualTo(CallStage.LOOKUP);
        assertThat(kind(failure)).isEqualTo(RemoteFailureKind.NOT_BOUND);
    }

    @Test
    @DisplayName("the node's reasons cross the wire: missing input, executor unavailable, block missing (METHOD_THREW, MatrixComputeException)")
    void reasonsCrossTheWire() throws Exception {
        fixture = new MatrixRmiFixture(BASE, MatrixRmiFixture.RUN);
        fixture.startAll();
        assertThat(reason(computeFails(2, 1))).isEqualTo(MatrixComputeException.Reason.INPUT_MISSING);

        put(3, 2);
        fixture.executors.put(3, task -> {
            throw new NodeDownException(3);
        });
        TransferException unavailable = computeFails(3, 2);
        assertThat(reason(unavailable)).isEqualTo(MatrixComputeException.Reason.EXECUTOR_UNAVAILABLE);
        assertThat(unavailable.original().getMessage()).isEqualTo("The node's executor is not running: the node is down (NodeDownException).");

        TransferException missing = catchThrowableOfType(() -> fixture.matrices.fetch(2, from(1), 99, 1), TransferException.class);
        assertThat(reason(missing)).isEqualTo(MatrixComputeException.Reason.BLOCK_MISSING);
    }

    @Test
    @DisplayName("a multiply that outlasts compute-timeout-millis: the node answers in time with COMPUTE_TIMED_OUT, entered once")
    void computeTimedOutOnNode() throws Exception {
        fixture = new MatrixRmiFixture(BASE, new MatrixRunProperties(1, 200, 4));
        fixture.startAll();
        put(2, 1);
        AtomicInteger entered = new AtomicInteger();
        fixture.executors.put(2, task -> {
            entered.incrementAndGet();
            return new CompletableFuture<>();
        });

        assertThat(reason(computeFails(2, 1))).isEqualTo(MatrixComputeException.Reason.COMPUTE_TIMED_OUT);
        assertThat(entered.get()).isEqualTo(1);
        assertThat(fixture.matrix(2).keptRunIds()).isEmpty();
    }
}
