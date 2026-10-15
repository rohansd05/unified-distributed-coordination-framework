package com.udcf.modules.matrix.dto;

import com.udcf.modules.matrix.ComputeRecord;
import com.udcf.modules.matrix.MatrixComputeException;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.RemoteFailureKind;

/**
 * One rank's compute: its rows (the per-node row allocation) and what happened. Every figure that was not
 * measured or does not apply is null, never 0.
 *
 * <p>No dedicated test: a record; tested through MatrixDtoMapperTest and MatrixControllerTest.</p>
 *
 * @param local              true for the root's own block (its own executor, no network)
 * @param roundTripNanos     remote DONE only, measured on the root (see computeRoundTripBasis)
 * @param timeToFailureNanos FAILED only, measured on the root; not a latency
 * @param computeNanos       DONE only, measured on the node around the multiply only
 * @param failureStage       remote FAILED only: LOOKUP or CALL
 * @param nodeReason         FAILED only, when the node (or the root, for its own block) said why
 * @param notAttemptedReason NOT_ATTEMPTED only
 */
public record ComputeDto(
        int rank,
        int nodeId,
        int rowStart,
        int rowCount,
        boolean local,
        ComputeRecord.Status status,
        Long roundTripNanos,
        Long timeToFailureNanos,
        Long computeNanos,
        String checksum,
        CallStage failureStage,
        RemoteFailureKind failureKind,
        String failureDescription,
        String failureClass,
        String failureMessage,
        String failureCauseClass,
        MatrixComputeException.Reason nodeReason,
        String nodeMessage,
        Long senderLamportAtSend,
        Long replyLamport,
        Long lamportAfterReply,
        String notAttemptedReason
) {
}
