package com.udcf.modules.mpi;

import com.udcf.modules.rmi.EndpointReply;

import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/**
 * A node's reply to {@link CollectiveEndpoint#contribute}: its block of a gather and the
 * checksum it computed over that block. The values are copied in and out, and equality compares
 * them element by element.
 *
 * @param nodeId       the contributing node
 * @param lamportTime  its Lamport time when it replied
 * @param collectiveId the gather
 * @param rank         the rank the block belongs to
 * @param values       the block
 * @param checksum     {@link VectorChecksum} of the block, as the node computed it
 */
public record Contribution(int nodeId, long lamportTime, long collectiveId, int rank, double[] values, String checksum)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public Contribution {
        EndpointReply.requireStamp(nodeId, lamportTime);
        if (collectiveId < 1) {
            throw new IllegalArgumentException("collectiveId must be >= 1, was " + collectiveId);
        }
        if (rank < 0) {
            throw new IllegalArgumentException("rank must be >= 0, was " + rank);
        }
        Objects.requireNonNull(values, "values must not be null");
        values = values.clone();
        if (!VectorChecksum.isChecksum(checksum)) {
            throw new IllegalArgumentException("checksum must be 64 lower-case hex characters, was " + checksum);
        }
    }

    /** A copy of the block. */
    @Override
    public double[] values() {
        return values.clone();
    }

    /** How many values the block has. */
    public int length() {
        return values.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Contribution that && nodeId == that.nodeId && lamportTime == that.lamportTime
                && collectiveId == that.collectiveId && rank == that.rank && Arrays.equals(values, that.values)
                && checksum.equals(that.checksum);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, lamportTime, collectiveId, rank, Arrays.hashCode(values), checksum);
    }

    @Override
    public String toString() {
        return "Contribution[nodeId=" + nodeId + ", lamportTime=" + lamportTime + ", collectiveId=" + collectiveId
                + ", rank=" + rank + ", length=" + values.length + ", checksum=" + checksum + "]";
    }
}
