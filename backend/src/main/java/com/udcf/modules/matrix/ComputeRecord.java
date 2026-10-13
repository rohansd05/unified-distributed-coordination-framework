package com.udcf.modules.matrix;

import com.udcf.modules.mpi.VectorChecksum;
import com.udcf.modules.rmi.RemoteFailureDetail;

import java.util.Objects;

/**
 * What happened to one rank's compute in a parallel matrix run (step E10b). Every figure that was not
 * measured or does not apply is null, never 0.
 *
 * <p><b>Two kinds of timeout stay distinguishable.</b> A node that could not finish its multiply within
 * compute-timeout-millis answers in time and says so: {@code failure.kind} METHOD_THREW with
 * {@code nodeReason} COMPUTE_TIMED_OUT. The root's own wait bound for the compute phase passing (a node
 * that did not answer at all) is {@code failure.kind} TIMED_OUT, observed by the root, with no
 * {@code nodeReason}.</p>
 *
 * @param rank                the rank
 * @param nodeId              the node of that rank
 * @param rowStart            its first row
 * @param rowCount            its rows
 * @param local               true for the root's own block (computed on the root's executor, no network)
 * @param status              DONE, FAILED or NOT_ATTEMPTED
 * @param roundTripNanos      remote DONE only: measured on the root around the compute call (lookup, call, the node's
 *                            queue wait and multiply, reply)
 * @param timeToFailureNanos  FAILED only, and always set there: measured on the root until the failure was known
 * @param computeNanos        DONE only: measured on the node, around the multiply only
 * @param checksum            DONE only: VectorChecksum of the computed rows, as the computing node reported it
 * @param failure             remote FAILED only, and always set there: stage, kind, class and message
 * @param nodeReason          FAILED only: why the node (or the root, for its own block) said it failed; null when
 *                            it said nothing (for example a refused connection); always set for a local FAILED
 * @param nodeMessage         set exactly with nodeReason
 * @param senderLamportAtSend remote only: the root's Lamport time in the call; null when nothing was sent
 * @param replyLamport        remote only: the Lamport time in the reply; null when no reply came back
 * @param lamportAfterReply   remote DONE only: the root's time after the receive rule
 * @param notAttemptedReason  NOT_ATTEMPTED only
 */
public record ComputeRecord(int rank, int nodeId, int rowStart, int rowCount, boolean local, Status status,
                            Long roundTripNanos, Long timeToFailureNanos, Long computeNanos, String checksum,
                            RemoteFailureDetail failure, MatrixComputeException.Reason nodeReason, String nodeMessage,
                            Long senderLamportAtSend, Long replyLamport, Long lamportAfterReply, String notAttemptedReason) {

    /**
     * How one compute ended.
     *
     * <p>No dedicated test: an enum without behaviour (R6).</p>
     */
    public enum Status {
        /** Computed; its block is on the node (or, for the root, in hand). */
        DONE,
        /** Tried and failed, with a reason. */
        FAILED,
        /** Never tried: its input did not arrive, or the run's deadline had passed. */
        NOT_ATTEMPTED
    }

    public ComputeRecord {
        Objects.requireNonNull(status, "status must not be null");
        if (rank < 0 || nodeId < 1 || rowStart < 0 || rowCount < 1) {
            throw new IllegalArgumentException("a compute needs rank >= 0, nodeId >= 1, rowStart >= 0 and rowCount >= 1");
        }
        boolean done = status == Status.DONE;
        boolean failed = status == Status.FAILED;
        check(done == (computeNanos != null), "computeNanos is set exactly for DONE");
        check(done == (checksum != null), "checksum is set exactly for DONE");
        check((done && !local) == (roundTripNanos != null), "roundTripNanos is set exactly for a remote DONE");
        check(failed == (timeToFailureNanos != null), "timeToFailureNanos is set exactly for FAILED");
        check((failed && !local) == (failure != null), "failure is set exactly for a remote FAILED");
        check((nodeReason == null) == (nodeMessage == null), "nodeReason and nodeMessage are set together");
        check(nodeReason == null || failed, "only a FAILED compute has a nodeReason");
        check(!(failed && local) || nodeReason != null, "a local FAILED compute has a nodeReason");
        check((status == Status.NOT_ATTEMPTED) == (notAttemptedReason != null),
                "notAttemptedReason is set exactly for NOT_ATTEMPTED");
        check((done && !local) == (lamportAfterReply != null), "lamportAfterReply is set exactly for a remote DONE");
        if (local || status == Status.NOT_ATTEMPTED) {
            check(senderLamportAtSend == null && replyLamport == null, "a " + (local ? "local" : status) + " compute has no Lamport time");
        }
        check(checksum == null || VectorChecksum.isChecksum(checksum), "checksum must be 64 lower-case hex characters");
        check(roundTripNanos == null || roundTripNanos >= 0, "roundTripNanos must be >= 0");
        check(timeToFailureNanos == null || timeToFailureNanos >= 0, "timeToFailureNanos must be >= 0");
        check(computeNanos == null || computeNanos >= 0, "computeNanos must be >= 0");
    }

    private static void check(boolean condition, String rule) {
        if (!condition) {
            throw new IllegalArgumentException(rule);
        }
    }
}
