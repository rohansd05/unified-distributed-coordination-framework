package com.udcf.modules.matrix;

import com.udcf.modules.matrix.InMemoryMatrixNodes.Step;
import com.udcf.modules.mpi.MpiProperties;
import com.udcf.modules.rmi.CallStage;

import java.net.ConnectException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Real {@link MatrixRunResult}s for the E10c unit tests, made by E10b's runner over the no-network
 * {@link InMemoryMatrixNodes} (real node logics) on three nodes, root 1, seed 42. No socket, no port.
 */
final class MatrixTestResults {

    /** The local profile's values (application.yml). */
    static final MpiProperties MPI = new MpiProperties(100_000, 10_000, 20, 8, 300);
    static final MatrixProperties MATRIX = new MatrixProperties(2, List.of(50, 100, 200, 316));
    static final MatrixRunProperties RUN = new MatrixRunProperties(1, 2000, 4);
    static final MatrixLimits LIMITS = new MatrixLimits(MATRIX, MPI);
    static final MatrixRunBudget BUDGET = new MatrixRunBudget(1000, 3000, 10_000, 2000, 1);
    /** A fixed processor count, so the basis texts do not depend on the machine. */
    static final SpeedupBasis BASIS = new SpeedupBasis(() -> 8);
    static final long SEED = 42;
    static final List<Integer> THREE = List.of(1, 2, 3);

    private MatrixTestResults() {
    }

    /** A thread-safe time source that moves 1000 ns on every read. */
    static LongSupplier stepping() {
        AtomicLong now = new AtomicLong();
        return () -> now.addAndGet(1_000);
    }

    static InMemoryMatrixNodes nodes() {
        return new InMemoryMatrixNodes(MPI, RUN, 1, 2, 3);
    }

    static MatrixRunResult run(long runId, int n, InMemoryMatrixNodes nodes, Set<Integer> live) {
        try (MatrixDispatchPool pool = new MatrixDispatchPool(3)) {
            return new ParallelMatrixExecutor(LIMITS, RUN, MPI, BUDGET, pool, stepping())
                    .run(new MatrixRunRequest(runId, n, SEED, 1, THREE, live), nodes.clock(1), nodes, nodes, nodes.executor(1));
        }
    }

    static MatrixRunResult complete(long runId, int n) {
        return run(runId, n, nodes(), Set.copyOf(THREE));
    }

    /** Node 3's compute is refused at the lookup: a hole, PARTIAL. */
    static MatrixRunResult partial(long runId, int n) {
        return run(runId, n, nodes().failing(Step.COMPUTE, 3, CallStage.LOOKUP,
                new ConnectException("Connection refused to host: 127.0.0.1")), Set.copyOf(THREE));
    }

    /** Both remote computes fail: FAILED, no fetch made. */
    static MatrixRunResult failed(long runId, int n) {
        return run(runId, n, nodes()
                .failing(Step.COMPUTE, 2, CallStage.LOOKUP, new ConnectException("Connection refused to host: 127.0.0.1"))
                .failing(Step.COMPUTE, 3, CallStage.LOOKUP, new ConnectException("Connection refused to host: 127.0.0.1")),
                Set.copyOf(THREE));
    }

    /** Node 2's fetched block is corrupted in flight: COMPLETE, but passed false. */
    static MatrixRunResult mismatch(long runId, int n) {
        return run(runId, n, nodes().rewriting(Step.FETCH, 2, reply -> {
            BlockReply block = (BlockReply) reply;
            double[] values = block.values();
            values[0] = values[0] + 1;
            return new BlockReply(block.nodeId(), block.lamportTime(), block.runId(), block.rank(), values);
        }), Set.copyOf(THREE));
    }

    /** Size 2 over three live nodes: SIZE_BELOW_PARTICIPANTS. */
    static MatrixRunResult refused(long runId) {
        return run(runId, 2, nodes(), Set.copyOf(THREE));
    }
}
