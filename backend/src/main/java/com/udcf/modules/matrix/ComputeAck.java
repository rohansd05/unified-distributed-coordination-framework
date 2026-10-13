package com.udcf.modules.matrix;

import com.udcf.modules.mpi.VectorChecksum;
import com.udcf.modules.rmi.EndpointReply;

import java.io.Serial;
import java.io.Serializable;

/**
 * A node's reply to {@link MatrixEndpoint#computeBlock}: small on purpose, so the compute and the
 * transfer of the block are measured separately. The block itself stays on the node until
 * {@link MatrixEndpoint#fetchBlock}.
 *
 * @param nodeId       the node that computed
 * @param lamportTime  its Lamport time when it replied
 * @param runId        the matrix run
 * @param rank         the rank whose rows it multiplied
 * @param rowCount     how many rows of C it computed
 * @param computeNanos measured on the node, around the multiply only (queue wait excluded)
 * @param checksum     {@link VectorChecksum} of the computed rows, as the node computed it
 */
public record ComputeAck(int nodeId, long lamportTime, long runId, int rank, int rowCount, long computeNanos, String checksum)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public ComputeAck {
        EndpointReply.requireStamp(nodeId, lamportTime);
        if (runId < 1) {
            throw new IllegalArgumentException("runId must be >= 1, was " + runId);
        }
        if (rank < 0) {
            throw new IllegalArgumentException("rank must be >= 0, was " + rank);
        }
        if (rowCount < 1) {
            throw new IllegalArgumentException("rowCount must be >= 1, was " + rowCount);
        }
        if (computeNanos < 0) {
            throw new IllegalArgumentException("computeNanos must be >= 0, was " + computeNanos);
        }
        if (!VectorChecksum.isChecksum(checksum)) {
            throw new IllegalArgumentException("checksum must be 64 lower-case hex characters, was " + checksum);
        }
    }
}
