package com.udcf.modules.matrix.dto;

import com.udcf.modules.mpi.CollectiveOperation;
import com.udcf.modules.mpi.CollectiveOutcome;
import com.udcf.modules.mpi.dto.RefusalDto;
import com.udcf.modules.mpi.dto.TransferDto;
import com.udcf.modules.mpi.dto.VerificationDto;

import java.util.List;

/**
 * The scatter of A or the broadcast of B of one run: Experiment 9's collective as it ran, WITHOUT its seed
 * (the seed of a matrix collective is derived from the user's seed, a full 64-bit value that must never go on
 * the wire). The root, ranks and skipped nodes are the run's own.
 *
 * <p>No dedicated test: a record; tested through MatrixDtoMapperTest and MatrixControllerTest.</p>
 *
 * @param collectiveId     1000000000 + 2 x run id (scatter) or that + 1 (broadcast)
 * @param refusal          set only if Experiment 9 refused the collective (not expected: the run refuses first)
 * @param inputChecksum    checksum of the whole of A or B as the root generated it
 * @param totalNanos       Experiment 9's total time (see the overview's phaseTimeLabel); null when no remote
 *                         transfer was attempted
 * @param payloadSizeBasis set exactly when a transfer has a size
 * @param latencyBasis     set exactly when a transfer has a latency
 */
public record MatrixPhaseDto(long collectiveId, CollectiveOperation operation, CollectiveOutcome outcome, RefusalDto refusal,
                             String inputChecksum, Long totalNanos, long deadlineMillis, List<TransferDto> transfers,
                             VerificationDto verification, String payloadSizeBasis, String latencyBasis) {
}
