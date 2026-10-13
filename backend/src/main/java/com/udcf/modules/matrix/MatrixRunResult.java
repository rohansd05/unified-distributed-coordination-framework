package com.udcf.modules.matrix;

import com.udcf.modules.mpi.CollectiveOutcome;
import com.udcf.modules.mpi.CollectiveResult;
import com.udcf.modules.mpi.TransferRecord;

import java.util.List;
import java.util.Objects;

/**
 * Everything one parallel matrix run did, as measured (step E10b). Nothing here is estimated; what was
 * not measured is null. The assembled C is not kept: only its checksums and counts (in
 * {@code verification}) and the times.
 *
 * @param runId                  the run
 * @param n                      the matrix size
 * @param seed                   the user's seed
 * @param rootNodeId             the root
 * @param rootRank               the root's rank; null exactly when REFUSED
 * @param rankNodeIds            the node of each rank, rank 0 first; empty exactly when REFUSED
 * @param skippedNodeIds         participants that were not live at the start, ascending
 * @param rowBlocks              each rank's rows, rank order; empty exactly when REFUSED
 * @param outcome                COMPLETE, PARTIAL, FAILED or REFUSED (from MatrixAssembly, or the refusal)
 * @param refusal                set exactly when REFUSED
 * @param deadlineMillis         the run deadline that applied ({@code udcf.mpi.collective-deadline-millis}), from just
 *                               before the scatter; it covers the scatter, the broadcast, the compute dispatch and the
 *                               fetches
 * @param payloadGenerationNanos measured on the root around the generation of A and B before the timed parts;
 *                               informational: it approximates the generation the scatter and the broadcast repeat
 *                               from the seeds inside the parallel run, and is NOT subtracted from any figure; null
 *                               exactly when REFUSED
 * @param scatter                Experiment 9's scatter of A (count n x n, block unit n); null exactly when REFUSED
 * @param broadcast              Experiment 9's broadcast of B (count n x n); null exactly when REFUSED
 * @param computes               one per rank, rank order; empty exactly when REFUSED
 * @param gather                 one transfer per rank, rank order (the root's is LOCAL); empty exactly when REFUSED
 * @param timing                 the times; null exactly when REFUSED
 * @param verification           the comparison with the sequential product; null exactly when REFUSED
 * @param inputsDelivered        A's blocks and B arrived intact on every remote rank: false if any check failed, null
 *                               if any is unknown, if there was no remote rank, or when REFUSED
 */
public record MatrixRunResult(long runId, int n, long seed, int rootNodeId, Integer rootRank, List<Integer> rankNodeIds,
                              List<Integer> skippedNodeIds, List<RowPartition.RowBlock> rowBlocks, CollectiveOutcome outcome,
                              MatrixRefusal refusal, long deadlineMillis, Long payloadGenerationNanos,
                              CollectiveResult scatter, CollectiveResult broadcast, List<ComputeRecord> computes,
                              List<TransferRecord> gather, MatrixTiming timing, MatrixVerification verification,
                              Boolean inputsDelivered) {

    public MatrixRunResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        rankNodeIds = List.copyOf(rankNodeIds);
        skippedNodeIds = List.copyOf(skippedNodeIds);
        rowBlocks = List.copyOf(rowBlocks);
        computes = List.copyOf(computes);
        gather = List.copyOf(gather);
        boolean refused = outcome == CollectiveOutcome.REFUSED;
        if (refused != (refusal != null)) {
            throw new IllegalArgumentException("refusal is set exactly for REFUSED");
        }
        if (refused) {
            if (rootRank != null || !rankNodeIds.isEmpty() || !rowBlocks.isEmpty() || !computes.isEmpty() || !gather.isEmpty()
                    || scatter != null || broadcast != null || timing != null || verification != null
                    || payloadGenerationNanos != null || inputsDelivered != null) {
                throw new IllegalArgumentException("a refused run has no ranks, transfers, times or verification");
            }
        } else {
            requireRan(rootRank, rankNodeIds, rowBlocks, computes, gather, payloadGenerationNanos, scatter, broadcast, timing,
                    verification);
        }
    }

    private static void requireRan(Integer rootRank, List<Integer> rankNodeIds, List<RowPartition.RowBlock> rowBlocks,
                                   List<ComputeRecord> computes, List<TransferRecord> gather, Long payloadGenerationNanos,
                                   CollectiveResult scatter, CollectiveResult broadcast, MatrixTiming timing,
                                   MatrixVerification verification) {
        Objects.requireNonNull(rootRank, "rootRank must not be null");
        Objects.requireNonNull(payloadGenerationNanos, "payloadGenerationNanos must not be null");
        Objects.requireNonNull(scatter, "scatter must not be null");
        Objects.requireNonNull(broadcast, "broadcast must not be null");
        Objects.requireNonNull(timing, "timing must not be null");
        Objects.requireNonNull(verification, "verification must not be null");
        int p = rankNodeIds.size();
        if (p == 0 || rowBlocks.size() != p || computes.size() != p || gather.size() != p || timing.rankComputes().size() != p) {
            throw new IllegalArgumentException("rows, computes, gather and rank computes need one entry per rank");
        }
        if (payloadGenerationNanos < 0) {
            throw new IllegalArgumentException("payloadGenerationNanos must be >= 0, was " + payloadGenerationNanos);
        }
    }
}
