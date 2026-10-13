package com.udcf.modules.mpi;

import java.util.Arrays;
import java.util.Objects;

/**
 * One rank's block of a gather's assembled vector. A block that did not arrive is a hole: its
 * values and checksum are null, never zeros. The values are copied in and out.
 *
 * @param rank     the rank
 * @param nodeId   the node of that rank
 * @param offset   the block's first index in the assembled vector
 * @param length   the block's length (block i of BlockPartition)
 * @param values   the block as it arrived (or as the root computed its own); null for a hole
 * @param checksum {@link VectorChecksum} of {@code values}, computed by the root; null for a hole
 */
public record GatheredBlock(int rank, int nodeId, int offset, int length, double[] values, String checksum) {

    public GatheredBlock {
        if ((values == null) != (checksum == null)) {
            throw new IllegalArgumentException("a hole has neither values nor checksum; a block has both");
        }
        if (values != null) {
            if (values.length != length) {
                throw new IllegalArgumentException("block " + rank + " must have " + length + " values, has " + values.length);
            }
            values = values.clone();
        }
    }

    /** Whether the block did not arrive. */
    public boolean hole() {
        return values == null;
    }

    /** A copy of the block; null for a hole. */
    @Override
    public double[] values() {
        return values == null ? null : values.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GatheredBlock that && rank == that.rank && nodeId == that.nodeId && offset == that.offset
                && length == that.length && Arrays.equals(values, that.values) && Objects.equals(checksum, that.checksum);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rank, nodeId, offset, length, Arrays.hashCode(values), checksum);
    }

    @Override
    public String toString() {
        return "GatheredBlock[rank=" + rank + ", nodeId=" + nodeId + ", offset=" + offset + ", length=" + length
                + (values == null ? ", hole" : ", checksum=" + checksum) + "]";
    }
}
