package com.udcf.modules.matrix;

import com.udcf.core.clock.LamportClock;
import com.udcf.modules.mpi.BlockPartition;
import com.udcf.modules.mpi.CollectiveExecutor;
import com.udcf.modules.mpi.CollectiveOperation;
import com.udcf.modules.mpi.CollectiveOutcome;
import com.udcf.modules.mpi.CollectiveRequest;
import com.udcf.modules.mpi.CollectiveResult;
import com.udcf.modules.mpi.MpiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** E10a's decision 1 in E10b: Experiment 9's scatter with block unit n hands out exactly RowPartition's rows of A. */
class RowAlignedScatterTest {

    private static final MpiProperties MPI = new MpiProperties(100_000, 10_000, 20, 8, 300);

    @Test
    @DisplayName("BlockPartition.of(n * n, p, n) has RowPartition's element ranges for n in {1, 2, 3, 7, 10, 16, 50} and p up to 7")
    void sameRanges() {
        for (int n : new int[] {1, 2, 3, 7, 10, 16, 50}) {
            for (int p = 1; p <= Math.min(n, 7); p++) {
                List<BlockPartition.Block> blocks = BlockPartition.of(n * n, p, n);
                List<RowPartition.RowBlock> rows = RowPartition.of(n, p);
                assertThat(blocks).as("n=%d p=%d", n, p).hasSameSizeAs(rows);
                for (int rank = 0; rank < p; rank++) {
                    assertThat(blocks.get(rank).offset()).isEqualTo(rows.get(rank).elementOffset());
                    assertThat(blocks.get(rank).length()).isEqualTo(rows.get(rank).elementLength());
                }
            }
        }
    }

    @Test
    @DisplayName("a scatter of seedA (count n x n, unit n) leaves exactly RowPartition.rows(A) on each node, a broadcast of seedB leaves B")
    void scatterDeliversRowsOfA() {
        int n = 10;
        long seed = 42;
        List<Integer> nodes = List.of(1, 2, 3);
        InMemoryMatrixNodes cluster = new InMemoryMatrixNodes(MPI, new MatrixRunProperties(1, 2000, 4), 1, 2, 3);
        AtomicLong now = new AtomicLong();
        CollectiveExecutor executor = new CollectiveExecutor(MPI, () -> now.getAndAdd(1000));

        CollectiveResult scatter = executor.execute(new CollectiveRequest(5, CollectiveOperation.SCATTER, 2, nodes, Set.copyOf(nodes),
                n * n, MatrixGenerator.seedA(seed), n), new LamportClock(), cluster);
        CollectiveResult broadcast = executor.execute(new CollectiveRequest(6, CollectiveOperation.BROADCAST, 2, nodes,
                Set.copyOf(nodes), n * n, MatrixGenerator.seedB(seed)), new LamportClock(), cluster);

        assertThat(scatter.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(broadcast.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        double[] a = MatrixGenerator.a(seed, n);
        List<RowPartition.RowBlock> rows = RowPartition.of(n, 3);
        assertThat(cluster.collective(1).received(5)).get().isEqualTo(RowPartition.rows(a, rows.get(0)));
        assertThat(cluster.collective(3).received(5)).get().isEqualTo(RowPartition.rows(a, rows.get(2)));
        assertThat(cluster.collective(3).received(6)).get().isEqualTo(MatrixGenerator.b(seed, n));
    }
}
