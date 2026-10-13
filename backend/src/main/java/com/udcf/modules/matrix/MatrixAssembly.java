package com.udcf.modules.matrix;

import com.udcf.modules.mpi.CollectiveOutcome;
import com.udcf.modules.mpi.VectorChecksum;

import java.util.List;
import java.util.Objects;

/**
 * Puts the gathered blocks of C together and checks them against the sequential product. Pure.
 *
 * <p><b>Outcome</b> (as in Experiment 9, judged on what arrived; a refusal comes from
 * {@link MatrixLimits#check}, never from here):</p>
 * <ul>
 *   <li>COMPLETE: no block is missing (also a run whose root is the only rank);</li>
 *   <li>FAILED: no block arrived at all, or at least one remote rank was needed and no remote block
 *       arrived;</li>
 *   <li>PARTIAL: otherwise, including when only the root's own block is missing.</li>
 * </ul>
 * <p>A failed transfer or compute is a hole in the list, so it is data, not an exception.</p>
 */
public final class MatrixAssembly {

    /**
     * The assembled product and what was proved about it.
     *
     * @param product      C, row-major; null while any block is missing (a hole is never filled with zeros)
     * @param verification the comparison with the sequential product
     * @param outcome      COMPLETE, PARTIAL or FAILED
     */
    public record Assembled(double[] product, MatrixVerification verification, CollectiveOutcome outcome) {

        public Assembled {
            Objects.requireNonNull(verification, "verification must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
            if (outcome == CollectiveOutcome.REFUSED) {
                throw new IllegalArgumentException("a refused run is never assembled");
            }
            if ((product == null) != (verification.blocksMissing() > 0)) {
                throw new IllegalArgumentException("the product exists exactly when no block is missing");
            }
            product = product == null ? null : product.clone();
        }

        @Override
        public double[] product() {
            return product == null ? null : product.clone();
        }
    }

    private MatrixAssembly() {
    }

    /**
     * Assembles {@code blocks} and verifies them.
     *
     * @param n          the matrix size
     * @param blocks     every rank's block, rank order, tiling rows [0, n); holes included
     * @param rootRank   the root's rank (its block was computed locally)
     * @param sequential the sequential product A x B, n x n
     * @throws IllegalArgumentException if the blocks do not tile the rows in rank order
     */
    public static Assembled assemble(int n, List<ResultBlock> blocks, int rootRank, double[] sequential) {
        Objects.requireNonNull(blocks, "blocks must not be null");
        Objects.requireNonNull(sequential, "sequential must not be null");
        if (sequential.length != MatrixGenerator.elements(n)) {
            throw new IllegalArgumentException("sequential has " + sequential.length + " values, not " + n + " x " + n);
        }
        if (rootRank < 0 || rootRank >= blocks.size()) {
            throw new IllegalArgumentException("rootRank " + rootRank + " is not one of the " + blocks.size() + " ranks");
        }
        requireTiling(n, blocks);

        double[] product = new double[sequential.length];
        int rowsReceived = 0;
        int missing = 0;
        int badChecksums = 0;
        long mismatched = 0;
        int remoteNeeded = 0;
        int remoteArrived = 0;
        for (ResultBlock result : blocks) {
            boolean remote = result.block().rank() != rootRank;
            if (remote) {
                remoteNeeded++;
            }
            if (result.hole()) {
                missing++;
                continue;
            }
            if (remote) {
                remoteArrived++;
            }
            double[] values = result.values();
            if (!VectorChecksum.of(values).equals(result.reportedChecksum())) {
                badChecksums++;
            }
            int offset = result.block().elementOffset();
            for (int i = 0; i < values.length; i++) {
                if (Double.doubleToLongBits(values[i]) != Double.doubleToLongBits(sequential[offset + i])) {
                    mismatched++;
                }
            }
            System.arraycopy(values, 0, product, offset, values.length);
            rowsReceived += result.block().rowCount();
        }
        boolean complete = missing == 0;
        MatrixVerification verification = MatrixVerification.of(n, rowsReceived, missing, badChecksums, mismatched,
                complete ? VectorChecksum.of(product) : null, VectorChecksum.of(sequential));
        CollectiveOutcome outcome;
        if (complete) {
            outcome = CollectiveOutcome.COMPLETE;
        } else if (rowsReceived == 0 || (remoteNeeded > 0 && remoteArrived == 0)) {
            outcome = CollectiveOutcome.FAILED;
        } else {
            outcome = CollectiveOutcome.PARTIAL;
        }
        return new Assembled(complete ? product : null, verification, outcome);
    }

    private static void requireTiling(int n, List<ResultBlock> blocks) {
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("there must be at least one block");
        }
        int nextRow = 0;
        for (int rank = 0; rank < blocks.size(); rank++) {
            RowPartition.RowBlock block = Objects.requireNonNull(blocks.get(rank), "a block must not be null").block();
            if (block.rank() != rank || block.n() != n || block.rowStart() != nextRow) {
                throw new IllegalArgumentException("blocks must tile rows [0, " + n + ") in rank order; block " + rank
                        + " is " + block);
            }
            nextRow = block.rowEnd();
        }
        if (nextRow != n) {
            throw new IllegalArgumentException("blocks must tile rows [0, " + n + "); they end at row " + nextRow);
        }
    }
}
