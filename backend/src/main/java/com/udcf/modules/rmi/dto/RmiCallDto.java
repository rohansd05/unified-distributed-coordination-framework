package com.udcf.modules.rmi.dto;

import com.udcf.modules.rmi.CallOutcome;
import com.udcf.modules.rmi.CallPath;
import com.udcf.modules.rmi.CallStage;
import com.udcf.modules.rmi.RemoteFailureKind;
import com.udcf.modules.rmi.RmiMethod;

import java.time.Instant;

/**
 * One call, as measured. A value that was not measured or does not apply is null, never 0 (R7).
 *
 * <p>No dedicated test: a record; built and tested in RmiDtoMapperTest.</p>
 *
 * @param id                      the history entry this call belongs to (both halves of a comparison
 *                                carry the comparison's id)
 * @param calledAt                wall time just before the call; display only
 * @param path                    LOCAL (a direct call on the endpoint object) or REMOTE (registry and stub)
 * @param outcome                 SUCCESS or REMOTE_FAILURE (a remote failure is data, not an error)
 * @param failureStage            LOOKUP or CALL; failures only
 * @param failureDescription      plain English for {@code failureKind}; failures only
 * @param lookupNanos             time of a successful lookup (see the overview's lookupBasis)
 * @param roundTripNanos          completed calls only
 * @param timeToFailureNanos      failures only: elapsed time of the stage that failed
 * @param argumentBytes           REMOTE only, once the lookup succeeded
 * @param resultBytes             REMOTE successes only
 * @param payloadSizeBasis        what the two sizes measure; set exactly when a size is set
 * @param stubClassName           the class of the object the call went through; set once a lookup succeeded
 * @param senderLamportAtSend     the caller's Lamport time sent with the call
 * @param replyLamport            the target's Lamport time on the reply
 * @param senderLamportAfterReply the caller's Lamport time after the receive rule
 * @param reply                   the reply; successes only
 */
public record RmiCallDto(
        long id,
        Instant calledAt,
        RmiMethod method,
        int fromNode,
        int targetNode,
        CallPath path,
        String argumentSummary,
        String resultSummary,
        CallOutcome outcome,
        CallStage failureStage,
        RemoteFailureKind failureKind,
        String failureDescription,
        String failureClass,
        String failureMessage,
        String failureCauseClass,
        Long lookupNanos,
        Long roundTripNanos,
        Long timeToFailureNanos,
        Long argumentBytes,
        Long resultBytes,
        String payloadSizeBasis,
        String stubClassName,
        Long senderLamportAtSend,
        Long replyLamport,
        Long senderLamportAfterReply,
        ReplyDto reply
) {
}
