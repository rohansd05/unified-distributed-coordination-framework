package com.udcf.modules.matrix.dto;

import com.udcf.modules.mpi.CollectiveOutcome;
import com.udcf.modules.mpi.dto.RootSource;
import com.udcf.modules.mpi.dto.TransferDto;

import java.time.Instant;
import java.util.List;

/**
 * One parallel matrix run as it ran, also its history entry. Nothing is estimated; what was not measured is
 * null. Neither A, B nor C is included (only checksums), and the only seed is the user's.
 *
 * <p>No dedicated test: a record; tested through MatrixDtoMapperTest and MatrixControllerTest.</p>
 *
 * @param runId                 the history id (restarts at 1 after a reset)
 * @param startedAt             wall time, display only
 * @param seed                  the user's seed (the run id when the request left it out)
 * @param rootRank              null when refused
 * @param rankNodeIds           the node of each rank, rank 0 first; empty when refused
 * @param skippedNodeIds        nodes that were crashed at the start, ascending
 * @param refusal               REFUSED only
 * @param deadlineMillis        the run deadline (udcf.mpi.collective-deadline-millis) from just before the scatter
 * @param scatter               the scatter of A; null when refused
 * @param broadcast             the broadcast of B; null when refused
 * @param computes              one per rank, rank order; empty when refused
 * @param gather                one fetch per rank, rank order (the root's part LOCAL); empty when refused
 * @param timing                null when refused
 * @param verification          null when refused
 * @param inputsDelivered       A's blocks and B arrived intact on every remote rank: false if any check failed, null
 *                              if unknown, without a remote rank, or when refused
 * @param speedupBasis          what bounds this run's speedup; null when refused
 * @param computeRoundTripBasis set exactly when a remote compute has a round trip
 * @param gatherLatencyBasis    set exactly when a fetch has a latency
 * @param payloadSizeBasis      set exactly when a fetch has a size
 */
public record MatrixRunDto(
        long runId,
        Instant startedAt,
        int size,
        long seed,
        int rootNodeId,
        RootSource rootSource,
        Integer rootRank,
        List<Integer> rankNodeIds,
        List<Integer> skippedNodeIds,
        CollectiveOutcome outcome,
        MatrixRefusalDto refusal,
        long deadlineMillis,
        MatrixPhaseDto scatter,
        MatrixPhaseDto broadcast,
        List<ComputeDto> computes,
        List<TransferDto> gather,
        MatrixTimingDto timing,
        MatrixVerificationDto verification,
        Boolean inputsDelivered,
        String speedupBasis,
        String computeRoundTripBasis,
        String gatherLatencyBasis,
        String payloadSizeBasis
) {
}
