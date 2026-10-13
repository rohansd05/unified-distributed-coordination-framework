package com.udcf.modules.mpi.dto;

import com.udcf.modules.mpi.TransferDirection;
import com.udcf.modules.mpi.TransferStatus;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.RemoteFailureKind;

/**
 * One rank's part of a collective (one row of the per-message table). Every figure that was not
 * measured or does not apply is null, never 0.
 *
 * <p>No dedicated test: a record; tested through MpiDtoMapperTest and MpiControllerTest.</p>
 *
 * @param status             LOCAL (the root's own part), DELIVERED, FAILED or NOT_ATTEMPTED
 * @param latencyNanos       DELIVERED only: the whole transfer (see the overview's latencyBasis)
 * @param timeToFailureNanos FAILED only
 * @param requestBytes       set when something was sent (see payloadSizeBasis)
 * @param replyBytes         set when a reply came back
 * @param verified           DELIVERED or LOCAL only; null when unknown
 * @param failureStage       FAILED only: LOOKUP (the node's endpoint was not reached) or CALL
 * @param failureDescription the failure kind in plain English
 * @param failureClass       the exception class; null for a reply that did not match its request
 * @param lamportAfterReply  DELIVERED only: the root's time after the receive rule
 * @param notAttemptedReason NOT_ATTEMPTED only
 */
public record TransferDto(
        int rank,
        int fromNodeId,
        int toNodeId,
        TransferDirection direction,
        TransferStatus status,
        int offset,
        int elementCount,
        Long latencyNanos,
        Long timeToFailureNanos,
        Long requestBytes,
        Long replyBytes,
        String expectedChecksum,
        String reportedChecksum,
        Boolean verified,
        CallStage failureStage,
        RemoteFailureKind failureKind,
        String failureDescription,
        String failureClass,
        String failureMessage,
        String failureCauseClass,
        Long senderLamportAtSend,
        Long replyLamport,
        Long lamportAfterReply,
        String notAttemptedReason
) {
}
