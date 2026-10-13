package com.udcf.modules.mpi;

import com.udcf.modules.rmi.EndpointReply;

import java.io.Serial;
import java.io.Serializable;

/**
 * A node's reply to {@link CollectiveEndpoint#receive}: what it actually received, so the root
 * can verify the transfer from the receiver's own figures.
 *
 * @param nodeId       the node that received
 * @param lamportTime  its Lamport time when it replied
 * @param collectiveId the collective the payload belongs to
 * @param rank         the rank the payload was sent to
 * @param elementCount how many values arrived
 * @param checksum     {@link VectorChecksum} of the values that arrived
 */
public record ReceiveReceipt(int nodeId, long lamportTime, long collectiveId, int rank, int elementCount, String checksum)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public ReceiveReceipt {
        EndpointReply.requireStamp(nodeId, lamportTime);
        if (collectiveId < 1) {
            throw new IllegalArgumentException("collectiveId must be >= 1, was " + collectiveId);
        }
        if (rank < 0) {
            throw new IllegalArgumentException("rank must be >= 0, was " + rank);
        }
        if (elementCount < 0) {
            throw new IllegalArgumentException("elementCount must be >= 0, was " + elementCount);
        }
        if (!VectorChecksum.isChecksum(checksum)) {
            throw new IllegalArgumentException("checksum must be 64 lower-case hex characters, was " + checksum);
        }
    }
}
