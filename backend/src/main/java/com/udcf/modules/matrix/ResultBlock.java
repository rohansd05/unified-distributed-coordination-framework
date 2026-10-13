package com.udcf.modules.matrix;

import java.util.Arrays;
import java.util.Objects;

/**
 * One rank's rows of C as they reached the root. A block that did not arrive (its compute or its
 * transfer failed, or was not attempted) is a hole: values and reported checksum are null, never
 * zeros. The values are copied in and out.
 *
 * @param block            the rows this rank was given
 * @param nodeId           the node of that rank
 * @param values           the rows as they arrived, row-major; null for a hole
 * @param reportedChecksum the checksum the computing node reported for them (VectorChecksum); null for a hole.
 *                         The root recomputes it over what arrived (MatrixAssembly).
 */
public record ResultBlock(RowPartition.RowBlock block, int nodeId, double[] values, String reportedChecksum) {

    public ResultBlock {
        Objects.requireNonNull(block, "block must not be null");
        if (nodeId < 1) {
            throw new IllegalArgumentException("nodeId must be >= 1, was " + nodeId);
        }
        if ((values == null) != (reportedChecksum == null)) {
            throw new IllegalArgumentException("a hole has neither values nor checksum; a block has both");
        }
        if (values != null) {
            if (values.length != block.elementLength()) {
                throw new IllegalArgumentException("block " + block.rank() + " must have " + block.elementLength()
                        + " values, has " + values.length);
            }
            values = values.clone();
        }
    }

    /** A block that did not arrive. */
    public static ResultBlock hole(RowPartition.RowBlock block, int nodeId) {
        return new ResultBlock(block, nodeId, null, null);
    }

    public boolean hole() {
        return values == null;
    }

    @Override
    public double[] values() {
        return values == null ? null : values.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ResultBlock that && block.equals(that.block) && nodeId == that.nodeId
                && Arrays.equals(values, that.values) && Objects.equals(reportedChecksum, that.reportedChecksum);
    }

    @Override
    public int hashCode() {
        return Objects.hash(block, nodeId, Arrays.hashCode(values), reportedChecksum);
    }

    @Override
    public String toString() {
        return "ResultBlock[block=" + block + ", nodeId=" + nodeId + ", values="
                + (values == null ? "null" : values.length + " values") + ", reportedChecksum=" + reportedChecksum + "]";
    }
}
