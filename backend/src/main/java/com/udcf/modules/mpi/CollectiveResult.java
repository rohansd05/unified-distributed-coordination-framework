package com.udcf.modules.mpi;

import java.util.List;
import java.util.Objects;

/**
 * Everything one collective did, as measured: who took part with which rank, who was skipped,
 * every transfer in the order it was made, the total time, the gathered blocks and what was
 * verified. Nothing here is estimated; what was not measured is null.
 *
 * @param collectiveId    the request's id
 * @param operation       broadcast, scatter or gather
 * @param rootNodeId      the root
 * @param rootRank        the root's rank; null when ranks could not be assigned
 * @param rankNodeIds     the node of each rank, rank 0 first; empty when ranks could not be assigned
 * @param skippedNodeIds  participants that were not live at the start, ascending
 * @param count           n
 * @param seed            the data's seed
 * @param outcome         COMPLETE, PARTIAL, FAILED or REFUSED
 * @param refusal         why it was refused; REFUSED only
 * @param inputChecksum   broadcast and scatter: the root's vector; gather: the expected assembled vector;
 *                        null when refused
 * @param transfers       one per rank, in the order they were made (rank order); empty when refused
 * @param totalNanos      from just before the first remote transfer to just after the last one, measured
 *                        separately (not a sum); null when no remote transfer was attempted
 * @param gathered        gather only: every rank's block in rank order (holes included); empty otherwise
 * @param verification    null when refused
 * @param deadlineMillis  the deadline that applied
 */
public record CollectiveResult(long collectiveId, CollectiveOperation operation, int rootNodeId, Integer rootRank,
                               List<Integer> rankNodeIds, List<Integer> skippedNodeIds, int count, long seed,
                               CollectiveOutcome outcome, Refusal refusal, String inputChecksum,
                               List<TransferRecord> transfers, Long totalNanos, List<GatheredBlock> gathered,
                               Verification verification, long deadlineMillis) {

    public CollectiveResult {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        rankNodeIds = List.copyOf(rankNodeIds);
        skippedNodeIds = List.copyOf(skippedNodeIds);
        transfers = List.copyOf(transfers);
        gathered = List.copyOf(gathered);
        boolean refused = outcome == CollectiveOutcome.REFUSED;
        if (refused != (refusal != null)) {
            throw new IllegalArgumentException("refusal is set exactly for REFUSED");
        }
        if (refused && !(transfers.isEmpty() && verification == null && totalNanos == null && inputChecksum == null)) {
            throw new IllegalArgumentException("a refused collective has no transfers, time, checksum or verification");
        }
    }

    /** The assembled gather result, or null if it has holes (missing ranks are never filled in). */
    public double[] assembled() {
        if (gathered.isEmpty() || gathered.stream().anyMatch(GatheredBlock::hole)) {
            return null;
        }
        double[] all = new double[count];
        for (GatheredBlock block : gathered) {
            System.arraycopy(block.values(), 0, all, block.offset(), block.length());
        }
        return all;
    }
}
