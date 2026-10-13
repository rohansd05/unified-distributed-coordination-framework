package com.udcf.modules.matrix;

import com.udcf.modules.mpi.CollectiveOutcome;
import com.udcf.modules.mpi.VectorChecksum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatrixAssemblyTest {

    private static final int N = 10;
    private static final double[] A = MatrixGenerator.a(42, N);
    private static final double[] B = MatrixGenerator.b(42, N);
    private static final double[] SEQUENTIAL = MatrixMultiply.multiply(A, B, N);

    /** Every rank's block, computed honestly; ranks are nodes 1 to p. */
    private static List<ResultBlock> blocks(int p) {
        List<ResultBlock> blocks = new ArrayList<>();
        for (RowPartition.RowBlock block : RowPartition.of(N, p)) {
            double[] rows = MatrixMultiply.multiplyRows(RowPartition.rows(A, block), B, N);
            blocks.add(new ResultBlock(block, block.rank() + 1, rows, VectorChecksum.of(rows)));
        }
        return blocks;
    }

    private static List<ResultBlock> withHole(List<ResultBlock> blocks, int rank) {
        List<ResultBlock> copy = new ArrayList<>(blocks);
        copy.set(rank, ResultBlock.hole(blocks.get(rank).block(), blocks.get(rank).nodeId()));
        return copy;
    }

    @Test
    @DisplayName("complete: the product equals the sequential one, checksums equal, passed, COMPLETE")
    void complete() {
        MatrixAssembly.Assembled result = MatrixAssembly.assemble(N, blocks(4), 0, SEQUENTIAL);
        assertThat(result.product()).isEqualTo(SEQUENTIAL);
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        MatrixVerification v = result.verification();
        assertThat(v.passed()).isTrue();
        assertThat(v.checksumsMatch()).isTrue();
        assertThat(v.parallelChecksum()).isEqualTo(v.sequentialChecksum()).isEqualTo(VectorChecksum.of(SEQUENTIAL));
        assertThat(v.rowsExpected()).isEqualTo(10);
        assertThat(v.rowsReceived()).isEqualTo(10);
        assertThat(v.elementsCompared()).isEqualTo(100);
        assertThat(v.mismatchedElements()).isZero();
        assertThat(v.blocksMissing()).isZero();
        assertThat(v.blocksWithBadChecksum()).isZero();
    }

    @Test
    @DisplayName("a missing block: no product, the whole-matrix fields are null, never passed; PARTIAL")
    void missingBlock() {
        MatrixAssembly.Assembled result = MatrixAssembly.assemble(N, withHole(blocks(4), 2), 0, SEQUENTIAL);
        assertThat(result.product()).isNull();
        assertThat(result.outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
        MatrixVerification v = result.verification();
        assertThat(v.passed()).isNull();
        assertThat(v.parallelChecksum()).isNull();
        assertThat(v.checksumsMatch()).isNull();
        assertThat(v.sequentialChecksum()).isEqualTo(VectorChecksum.of(SEQUENTIAL));
        assertThat(v.rowsReceived()).isEqualTo(8);
        assertThat(v.blocksMissing()).isEqualTo(1);
        assertThat(v.elementsCompared()).isEqualTo(80);
        assertThat(v.mismatchedElements()).isZero();
    }

    @Test
    @DisplayName("outcomes: every remote block missing is FAILED; only the root's missing is PARTIAL; root alone is COMPLETE, or FAILED without its block")
    void outcomes() {
        List<ResultBlock> onlyRoot = withHole(withHole(withHole(blocks(4), 1), 2), 3);
        assertThat(MatrixAssembly.assemble(N, onlyRoot, 0, SEQUENTIAL).outcome()).isEqualTo(CollectiveOutcome.FAILED);
        assertThat(MatrixAssembly.assemble(N, withHole(blocks(4), 0), 0, SEQUENTIAL).outcome()).isEqualTo(CollectiveOutcome.PARTIAL);
        assertThat(MatrixAssembly.assemble(N, withHole(blocks(1), 0), 0, SEQUENTIAL).outcome()).isEqualTo(CollectiveOutcome.FAILED);
        assertThat(MatrixAssembly.assemble(N, blocks(1), 0, SEQUENTIAL).outcome()).isEqualTo(CollectiveOutcome.COMPLETE);
    }

    @Test
    @DisplayName("one corrupted element (with a matching checksum): 1 mismatch, checksums differ, not passed")
    void corruptedElement() {
        List<ResultBlock> blocks = blocks(4);
        ResultBlock original = blocks.get(1);
        double[] values = original.values();
        values[5] = Math.nextUp(values[5]);
        blocks.set(1, new ResultBlock(original.block(), original.nodeId(), values, VectorChecksum.of(values)));
        MatrixVerification v = MatrixAssembly.assemble(N, blocks, 0, SEQUENTIAL).verification();
        assertThat(v.mismatchedElements()).isEqualTo(1);
        assertThat(v.blocksWithBadChecksum()).isZero();
        assertThat(v.checksumsMatch()).isFalse();
        assertThat(v.passed()).isFalse();
    }

    @Test
    @DisplayName("a reported checksum that does not match what arrived is counted, and not passed")
    void corruptedChecksum() {
        List<ResultBlock> blocks = blocks(4);
        ResultBlock original = blocks.get(3);
        blocks.set(3, new ResultBlock(original.block(), original.nodeId(), original.values(), VectorChecksum.of(new double[]{1.0})));
        MatrixVerification v = MatrixAssembly.assemble(N, blocks, 0, SEQUENTIAL).verification();
        assertThat(v.blocksWithBadChecksum()).isEqualTo(1);
        assertThat(v.mismatchedElements()).isZero();
        assertThat(v.checksumsMatch()).isTrue();
        assertThat(v.passed()).isFalse();
    }

    @Test
    @DisplayName("a known mismatch with a block missing is still false, never null")
    void mismatchWithHole() {
        List<ResultBlock> blocks = withHole(blocks(4), 3);
        ResultBlock original = blocks.get(1);
        double[] values = original.values();
        values[0] = -values[0];
        blocks.set(1, new ResultBlock(original.block(), original.nodeId(), values, VectorChecksum.of(values)));
        MatrixVerification v = MatrixAssembly.assemble(N, blocks, 0, SEQUENTIAL).verification();
        assertThat(v.passed()).isFalse();
        assertThat(v.checksumsMatch()).isNull();
        assertThat(v.mismatchedElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("blocks that do not tile the rows in rank order, or a bad root rank or size, are refused")
    void badInput() {
        List<ResultBlock> blocks = blocks(4);
        List<ResultBlock> swapped = List.of(blocks.get(1), blocks.get(0), blocks.get(2), blocks.get(3));
        assertThatThrownBy(() -> MatrixAssembly.assemble(N, swapped, 0, SEQUENTIAL)).hasMessageContaining("rank order");
        assertThatThrownBy(() -> MatrixAssembly.assemble(N, blocks.subList(0, 3), 0, SEQUENTIAL)).hasMessageContaining("end at row 8");
        assertThatThrownBy(() -> MatrixAssembly.assemble(N, List.of(), 0, SEQUENTIAL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MatrixAssembly.assemble(N, blocks, 4, SEQUENTIAL)).hasMessageContaining("rootRank 4");
        assertThatThrownBy(() -> MatrixAssembly.assemble(N, blocks, 0, new double[99])).hasMessageContaining("sequential has 99");
    }
}
