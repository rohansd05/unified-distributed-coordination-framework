package com.udcf.modules.mpi.dto;

import com.udcf.modules.mpi.CollectiveOperation;
import com.udcf.modules.mpi.CollectiveOutcome;

import java.time.Instant;
import java.util.List;

/**
 * One collective as it ran, also its history entry. Nothing is estimated; what was not measured is
 * null. The vectors themselves are not included (only checksums).
 *
 * <p>No dedicated test: a record; tested through MpiDtoMapperTest and MpiControllerTest.</p>
 *
 * @param id               the history id, also the collective id sent to every node (restarts at 1 after a reset)
 * @param startedAt        wall time, display only
 * @param rootSource       which rule chose the root
 * @param rootRank         null when refused
 * @param rankNodeIds      the node of each rank, rank 0 first; empty when refused
 * @param skippedNodeIds   nodes that were crashed at the start, ascending
 * @param seed             the seed used (the id when the request left it out)
 * @param refusal          REFUSED only
 * @param inputChecksum    broadcast and scatter: the root's vector; gather: the expected assembled vector; null
 *                         when refused
 * @param totalNanos       from just before the first remote transfer to just after the last; null when none was
 *                         attempted
 * @param gathered         gather only (holes included); empty otherwise
 * @param verification     null when refused
 * @param payloadSizeBasis set exactly when a transfer has a size
 * @param latencyBasis     set exactly when a transfer has a latency
 */
public record CollectiveDto(
        long id,
        Instant startedAt,
        CollectiveOperation operation,
        int rootNodeId,
        RootSource rootSource,
        Integer rootRank,
        List<Integer> rankNodeIds,
        List<Integer> skippedNodeIds,
        int count,
        long seed,
        CollectiveOutcome outcome,
        RefusalDto refusal,
        String inputChecksum,
        Long totalNanos,
        long deadlineMillis,
        List<TransferDto> transfers,
        List<GatheredBlockDto> gathered,
        VerificationDto verification,
        String payloadSizeBasis,
        String latencyBasis
) {
}
