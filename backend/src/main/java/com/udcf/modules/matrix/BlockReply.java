package com.udcf.modules.matrix;

import com.udcf.modules.rmi.EndpointReply;

import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/**
 * A node's reply to {@link MatrixEndpoint#fetchBlock}: the rows of C it computed for a run. The
 * values are copied in and out, and equality compares them element by element. The root recomputes
 * the checksum over what arrived and compares it with the one in the {@link ComputeAck}.
 *
 * @param nodeId      the node that answered
 * @param lamportTime its Lamport time when it replied
 * @param runId       the matrix run
 * @param rank        the rank the rows belong to
 * @param values      the rows, row-major
 */
public record BlockReply(int nodeId, long lamportTime, long runId, int rank, double[] values) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public BlockReply {
        EndpointReply.requireStamp(nodeId, lamportTime);
        if (runId < 1) {
            throw new IllegalArgumentException("runId must be >= 1, was " + runId);
        }
        if (rank < 0) {
            throw new IllegalArgumentException("rank must be >= 0, was " + rank);
        }
        values = Objects.requireNonNull(values, "values must not be null").clone();
    }

    /** A copy of the rows. */
    @Override
    public double[] values() {
        return values.clone();
    }

    /** How many values arrived. */
    public int length() {
        return values.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BlockReply that && nodeId == that.nodeId && lamportTime == that.lamportTime
                && runId == that.runId && rank == that.rank && Arrays.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, lamportTime, runId, rank, Arrays.hashCode(values));
    }

    @Override
    public String toString() {
        return "BlockReply[nodeId=" + nodeId + ", lamportTime=" + lamportTime + ", runId=" + runId + ", rank=" + rank
                + ", length=" + values.length + "]";
    }
}
