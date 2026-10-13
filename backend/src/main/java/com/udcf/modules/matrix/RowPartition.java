package com.udcf.modules.matrix;

import com.udcf.modules.mpi.BlockPartition;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Row-aligned blocks of an n x n matrix over p ranks: Experiment 9's {@link BlockPartition} applied
 * to rows instead of elements. Each rank gets whole rows, {@code base = n / p}, and the first
 * {@code n % p} ranks one more, in rank order; block i belongs to rank i. Fewer rows than ranks
 * (n &lt; p) is refused, as Experiment 9 refuses fewer elements than ranks.
 *
 * <p>Example: n = 10 over p = 4 gives 3, 3, 2, 2 rows starting at rows 0, 3, 6, 8, that is element
 * offsets 0, 30, 60, 80 and lengths 30, 30, 20, 20.</p>
 */
public final class RowPartition {

    /**
     * One rank's rows.
     *
     * @param rank     the rank
     * @param rowStart the first row
     * @param rowCount how many rows (at least 1)
     * @param n        the matrix size
     */
    public record RowBlock(int rank, int rowStart, int rowCount, int n) {

        public RowBlock {
            if (rank < 0 || rowStart < 0 || rowCount < 1 || n < 1 || rowStart + rowCount > n) {
                throw new IllegalArgumentException("a row block needs rank >= 0, rowStart >= 0, rowCount >= 1 and rows inside n, was "
                        + rank + ", " + rowStart + ", " + rowCount + ", " + n);
            }
        }

        /** The row just after the block. */
        public int rowEnd() {
            return rowStart + rowCount;
        }

        /** The block's first index in the row-major matrix. */
        public int elementOffset() {
            return Math.multiplyExact(rowStart, n);
        }

        /** The block's number of elements. */
        public int elementLength() {
            return Math.multiplyExact(rowCount, n);
        }
    }

    private RowPartition() {
    }

    /**
     * The p row blocks of an n x n matrix, rank 0 first.
     *
     * @throws IllegalArgumentException if p &lt; 1 or n &lt; p
     */
    public static List<RowBlock> of(int n, int p) {
        return BlockPartition.of(n, p).stream()
                .map(rows -> new RowBlock(rows.rank(), rows.offset(), rows.length(), n))
                .toList();
    }

    /** A copy of {@code block}'s rows of the row-major n x n {@code matrix}. */
    public static double[] rows(double[] matrix, RowBlock block) {
        Objects.requireNonNull(matrix, "matrix must not be null");
        Objects.requireNonNull(block, "block must not be null");
        if (matrix.length != MatrixGenerator.elements(block.n())) {
            throw new IllegalArgumentException("matrix has " + matrix.length + " values, not " + block.n() + " x " + block.n());
        }
        return Arrays.copyOfRange(matrix, block.elementOffset(), block.elementOffset() + block.elementLength());
    }
}
