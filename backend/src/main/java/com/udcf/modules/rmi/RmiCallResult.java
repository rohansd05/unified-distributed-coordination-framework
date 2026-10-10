package com.udcf.modules.rmi;

import java.util.Objects;

/**
 * Everything measured about one {@link NodeEndpoint} call, built by {@link RmiInvoker}.
 *
 * <p><b>Null, never 0 (R7).</b> A value that was not measured or does not apply is
 * {@code null}. The factories {@link #success}, {@link #lookupFailure} and {@link #callFailure}
 * are the intended way to build one, and the constructor enforces the same rules:</p>
 * <ul>
 *   <li><b>SUCCESS</b>: reply, result summary, lookup and round-trip times, stub class and the
 *       three Lamport values are set; every failure field and {@code timeToFailureNanos} is
 *       null. The byte sizes are set on the REMOTE path and null on the LOCAL path (nothing is
 *       marshalled).</li>
 *   <li><b>REMOTE_FAILURE at LOOKUP</b> (REMOTE path only): the lookup did not complete and the
 *       method was never called, so {@code lookupNanos}, the round trip, sizes, stub class and
 *       Lamport values are null; the measured time until the lookup failed is
 *       {@code timeToFailureNanos}.</li>
 *   <li><b>REMOTE_FAILURE at CALL</b> (REMOTE path only): the lookup completed, so
 *       {@code lookupNanos} is set; {@code roundTripNanos} is null, because nothing came back;
 *       the measured time until the call failed is {@code timeToFailureNanos}. The reply, result
 *       size, reply Lamport and the caller's Lamport after the reply are null.</li>
 * </ul>
 *
 * <p>So a duration is only ever reported for what it measured: {@code lookupNanos} for a
 * completed lookup, {@code roundTripNanos} for a completed call, and {@code timeToFailureNanos}
 * for the stage that failed.</p>
 *
 * <p><b>Text.</b> Summaries and the failure message are single-line ({@link DisplayText}). The
 * failure message is the exception's own message only (never a stack trace), cut to
 * {@code max-failure-message-length} with a trailing ellipsis; null if the exception had none.</p>
 *
 * <p>Sizes are {@link PayloadSizer#LABEL}.</p>
 *
 * @param method                  the method called
 * @param targetNodeId            the node called
 * @param senderNodeId            the calling node
 * @param path                    LOCAL (direct) or REMOTE (stub)
 * @param argumentSummary         the user-visible argument, one line
 * @param resultSummary           the reply, one line; null on failure
 * @param reply                   the reply; null on failure
 * @param lookupNanos             time of a successful lookup; null if the lookup failed
 * @param roundTripNanos          measured around a completed method call; null on any failure
 * @param timeToFailureNanos      elapsed time of the stage that failed (LOOKUP or CALL); null on success
 * @param argumentBytes           serialization size of the arguments; REMOTE, after a lookup
 * @param resultBytes             serialization size of the reply; REMOTE success only
 * @param stubClassName           the class of the endpoint object the call went through
 * @param outcome                 SUCCESS or REMOTE_FAILURE
 * @param failureStage            LOOKUP or CALL; failure only
 * @param failureKind             the classified failure; failure only
 * @param failureClass            the exception's class name; failure only
 * @param failureMessage          the exception's message, one line, cut; null if it had none
 * @param failureCauseClass       the innermost cause's class name; null if there is no cause
 * @param senderLamportAtSend     the caller's Lamport time sent with the call
 * @param replyLamport            the node's Lamport time on the reply
 * @param senderLamportAfterReply the caller's Lamport time after applying the receive rule
 */
public record RmiCallResult(
        RmiMethod method,
        int targetNodeId,
        int senderNodeId,
        CallPath path,
        String argumentSummary,
        String resultSummary,
        EndpointReply reply,
        Long lookupNanos,
        Long roundTripNanos,
        Long timeToFailureNanos,
        Long argumentBytes,
        Long resultBytes,
        String stubClassName,
        CallOutcome outcome,
        CallStage failureStage,
        RemoteFailureKind failureKind,
        String failureClass,
        String failureMessage,
        String failureCauseClass,
        Long senderLamportAtSend,
        Long replyLamport,
        Long senderLamportAfterReply
) {

    public RmiCallResult {
        require(method != null, "method must not be null");
        require(path != null, "path must not be null");
        require(outcome != null, "outcome must not be null");
        require(targetNodeId >= 1, "targetNodeId must be >= 1, was " + targetNodeId);
        require(senderNodeId >= 1, "senderNodeId must be >= 1, was " + senderNodeId);
        require(argumentSummary != null, "argumentSummary must not be null");
        requireSingleLine("argumentSummary", argumentSummary);
        requireSingleLine("resultSummary", resultSummary);
        requireSingleLine("failureMessage", failureMessage);
        requireNonNegative("lookupNanos", lookupNanos);
        requireNonNegative("roundTripNanos", roundTripNanos);
        requireNonNegative("timeToFailureNanos", timeToFailureNanos);
        requireNonNegative("argumentBytes", argumentBytes);
        requireNonNegative("resultBytes", resultBytes);
        requireNonNegative("senderLamportAtSend", senderLamportAtSend);
        requireNonNegative("replyLamport", replyLamport);
        requireNonNegative("senderLamportAfterReply", senderLamportAfterReply);
        boolean remote = path == CallPath.REMOTE;
        if (outcome == CallOutcome.SUCCESS) {
            require(reply != null && resultSummary != null && lookupNanos != null && roundTripNanos != null
                            && stubClassName != null
                            && senderLamportAtSend != null && replyLamport != null && senderLamportAfterReply != null,
                    "a success needs the reply, its summary, the lookup and round-trip times, the stub class and "
                            + "the Lamport values");
            require(reply.nodeId() == targetNodeId,
                    "the reply came from node " + reply.nodeId() + ", not the target " + targetNodeId);
            require(replyLamport == reply.lamportTime(), "replyLamport must be the reply's Lamport time");
            require(timeToFailureNanos == null && failureStage == null && failureKind == null && failureClass == null
                            && failureMessage == null && failureCauseClass == null,
                    "a success has no failure fields");
            require(remote == (argumentBytes != null) && remote == (resultBytes != null),
                    "sizes are measured on the REMOTE path only");
        } else {
            require(remote, "only a REMOTE call can end in REMOTE_FAILURE");
            require(failureStage != null && failureKind != null && failureClass != null && timeToFailureNanos != null,
                    "a failure needs its stage, kind, class and time to failure");
            require(reply == null && resultSummary == null && roundTripNanos == null && resultBytes == null
                            && replyLamport == null && senderLamportAfterReply == null,
                    "a failure has no reply, round trip, result size or reply Lamport time");
            boolean called = failureStage == CallStage.CALL;
            require(called == (lookupNanos != null) && called == (argumentBytes != null)
                            && called == (stubClassName != null) && called == (senderLamportAtSend != null),
                    "lookup time, argument size, stub class and sent Lamport time exist exactly when the "
                            + "lookup completed and the call was made (stage CALL)");
        }
    }

    /** A call that returned {@code reply}. Sizes must be null on the LOCAL path. */
    public static RmiCallResult success(RmiCall call, int targetNodeId, int senderNodeId, CallPath path,
                                        EndpointReply reply, long lookupNanos, long roundTripNanos,
                                        Long argumentBytes, Long resultBytes, String stubClassName,
                                        long senderLamportAtSend, long senderLamportAfterReply) {
        Objects.requireNonNull(call, "call must not be null");
        Objects.requireNonNull(reply, "reply must not be null");
        return new RmiCallResult(call.method(), targetNodeId, senderNodeId, path, call.argumentSummary(),
                RmiCall.resultSummary(reply), reply, lookupNanos, roundTripNanos, null, argumentBytes, resultBytes,
                stubClassName, CallOutcome.SUCCESS, null, null, null, null, null,
                senderLamportAtSend, reply.lamportTime(), senderLamportAfterReply);
    }

    /**
     * A remote call whose endpoint could not be obtained after {@code timeToFailureNanos}: the
     * method was never called.
     */
    public static RmiCallResult lookupFailure(RmiCall call, int targetNodeId, int senderNodeId,
                                              long timeToFailureNanos, Throwable failure,
                                              int maxFailureMessageLength) {
        Objects.requireNonNull(call, "call must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        return new RmiCallResult(call.method(), targetNodeId, senderNodeId, CallPath.REMOTE, call.argumentSummary(),
                null, null, null, null, timeToFailureNanos, null, null, null, CallOutcome.REMOTE_FAILURE,
                CallStage.LOOKUP,
                RemoteFailureClassifier.classify(failure, CallStage.LOOKUP), failure.getClass().getName(),
                failureMessage(failure, maxFailureMessageLength), causeClass(failure), null, null, null);
    }

    /** A remote call that was made and failed after {@code timeToFailureNanos}. */
    public static RmiCallResult callFailure(RmiCall call, int targetNodeId, int senderNodeId, long lookupNanos,
                                            long timeToFailureNanos, long argumentBytes, String stubClassName,
                                            long senderLamportAtSend, Throwable failure,
                                            int maxFailureMessageLength) {
        Objects.requireNonNull(call, "call must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        return new RmiCallResult(call.method(), targetNodeId, senderNodeId, CallPath.REMOTE, call.argumentSummary(),
                null, null, lookupNanos, null, timeToFailureNanos, argumentBytes, null, stubClassName,
                CallOutcome.REMOTE_FAILURE, CallStage.CALL, RemoteFailureClassifier.classify(failure, CallStage.CALL),
                failure.getClass().getName(), failureMessage(failure, maxFailureMessageLength), causeClass(failure),
                senderLamportAtSend, null, null);
    }

    public boolean succeeded() {
        return outcome == CallOutcome.SUCCESS;
    }

    /** The exception's own message, single-line and cut; never a stack trace. */
    static String failureMessage(Throwable failure, int maxLength) {
        return DisplayText.singleLine(failure.getMessage(), maxLength);
    }

    private static String causeClass(Throwable failure) {
        Throwable cause = RemoteFailureClassifier.innermostCause(failure);
        return cause == null ? null : cause.getClass().getName();
    }

    private static void requireSingleLine(String name, String value) {
        if (value != null && !DisplayText.isSingleLine(value)) {
            throw new IllegalArgumentException(name + " must be a single line without control characters");
        }
    }

    private static void requireNonNegative(String name, Long value) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, was " + value);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
