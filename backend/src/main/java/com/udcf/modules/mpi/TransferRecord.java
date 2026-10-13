package com.udcf.modules.mpi;

import com.udcf.modules.rmi.RemoteFailureDetail;

import java.util.Objects;

/**
 * One rank's part of a collective, for the per-message table: sender, receiver, size, latency,
 * and what was verified. Every figure that was not measured or does not apply is null, never 0.
 *
 * @param rank               the rank
 * @param fromNodeId         the sender (the root for TO_RANK, the rank's node for FROM_RANK)
 * @param toNodeId           the receiver
 * @param direction          which way the data went
 * @param status             LOCAL, DELIVERED, FAILED or NOT_ATTEMPTED
 * @param offset             the part's first index in the collective's vector (0 for a broadcast)
 * @param elementCount       the part's planned length
 * @param latencyNanos       measured around the transport call; DELIVERED only (a latency is a completed, accepted exchange)
 * @param timeToFailureNanos FAILED only, and always set there: from just before the transport call until the failure
 *                           or the mismatch was detected, by the same timer as latencyNanos. The executor times every
 *                           call itself, so no FAILED transfer lacks it; null for every other status, never 0 by default
 * @param requestBytes       Java serialization size of the call's arguments (PayloadSizer.LABEL); set when sent
 * @param replyBytes         Java serialization size of the reply; set when a reply came back
 * @param expectedChecksum   the checksum the part should have
 * @param reportedChecksum   the checksum the other side reported (gather: the one the node sent with its block)
 * @param verified           true or false for a DELIVERED or LOCAL part; null when unknown
 * @param failure            stage, kind, class and message; FAILED only
 * @param senderLamportAtSend the sender's Lamport time in the request; null when nothing was sent
 * @param replyLamport       the Lamport time in the reply; null when no reply came back
 * @param lamportAfterReply  the root's time after the receive rule; DELIVERED only
 * @param notAttemptedReason why it was not tried; NOT_ATTEMPTED only
 */
public record TransferRecord(int rank, int fromNodeId, int toNodeId, TransferDirection direction, TransferStatus status,
                             int offset, int elementCount, Long latencyNanos, Long timeToFailureNanos, Long requestBytes,
                             Long replyBytes, String expectedChecksum, String reportedChecksum, Boolean verified,
                             RemoteFailureDetail failure, Long senderLamportAtSend, Long replyLamport, Long lamportAfterReply,
                             String notAttemptedReason) {

    public TransferRecord {
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (rank < 0 || offset < 0 || elementCount < 1) {
            throw new IllegalArgumentException("rank and offset must be >= 0 and elementCount >= 1");
        }
        boolean delivered = status == TransferStatus.DELIVERED;
        check(delivered == (latencyNanos != null), "latencyNanos is set exactly for DELIVERED");
        check(delivered == (lamportAfterReply != null), "lamportAfterReply is set exactly for DELIVERED");
        check((status == TransferStatus.FAILED) == (failure != null), "failure is set exactly for FAILED");
        check((status == TransferStatus.FAILED) == (timeToFailureNanos != null), "timeToFailureNanos is set exactly for FAILED");
        check((status == TransferStatus.NOT_ATTEMPTED) == (notAttemptedReason != null),
                "notAttemptedReason is set exactly for NOT_ATTEMPTED");
        if (status == TransferStatus.LOCAL || status == TransferStatus.NOT_ATTEMPTED) {
            check(requestBytes == null && replyBytes == null && senderLamportAtSend == null && replyLamport == null,
                    "a " + status + " part has no size and no Lamport time");
        }
        if (status != TransferStatus.DELIVERED && status != TransferStatus.LOCAL) {
            check(verified == null, "only a DELIVERED or LOCAL part can be verified");
        }
        check(latencyNanos == null || latencyNanos >= 0, "latencyNanos must be >= 0");
        check(timeToFailureNanos == null || timeToFailureNanos >= 0, "timeToFailureNanos must be >= 0");
    }

    private static void check(boolean condition, String rule) {
        if (!condition) {
            throw new IllegalArgumentException(rule);
        }
    }
}
