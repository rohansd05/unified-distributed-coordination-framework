package com.udcf.modules.matrix;

import com.udcf.modules.mpi.VectorChecksum;

import java.util.Objects;

/**
 * Whether the parallel product equals the sequential one, judged only from what arrived. While any
 * block is missing the whole matrix is unknown: its checksum, the checksum comparison and
 * {@code passed} are null, never "passed".
 *
 * @param rowsExpected          n
 * @param rowsReceived          rows of the blocks that arrived
 * @param blocksMissing         blocks that did not arrive (holes)
 * @param blocksWithBadChecksum arrived blocks whose reported checksum differs from the root's recomputation
 * @param elementsCompared      elements of the arrived rows, each compared with the sequential product
 * @param mismatchedElements    of those, the elements whose bits differ ({@code Double.doubleToLongBits});
 *                              counted over the arrived rows only
 * @param parallelChecksum      VectorChecksum of the assembled product; null while a block is missing
 * @param sequentialChecksum    VectorChecksum of the sequential product
 * @param checksumsMatch        whether the two checksums are equal; null while a block is missing
 * @param passed                false on any known mismatch (an element, a bad checksum, or the checksums);
 *                              otherwise null while a block is missing; otherwise true
 */
public record MatrixVerification(int rowsExpected, int rowsReceived, int blocksMissing, int blocksWithBadChecksum,
                                 long elementsCompared, long mismatchedElements, String parallelChecksum,
                                 String sequentialChecksum, Boolean checksumsMatch, Boolean passed) {

    public MatrixVerification {
        if (rowsExpected < 1 || rowsReceived < 0 || rowsReceived > rowsExpected || blocksMissing < 0
                || blocksWithBadChecksum < 0 || elementsCompared != (long) rowsReceived * rowsExpected
                || mismatchedElements < 0 || mismatchedElements > elementsCompared) {
            throw new IllegalArgumentException("inconsistent counts: rows " + rowsReceived + " of " + rowsExpected
                    + ", missing " + blocksMissing + ", bad checksums " + blocksWithBadChecksum + ", compared "
                    + elementsCompared + ", mismatched " + mismatchedElements);
        }
        if (!VectorChecksum.isChecksum(sequentialChecksum)) {
            throw new IllegalArgumentException("sequentialChecksum must be a checksum, was " + sequentialChecksum);
        }
        boolean complete = blocksMissing == 0;
        if (complete != (rowsReceived == rowsExpected)) {
            throw new IllegalArgumentException("no block may be missing exactly when every row arrived");
        }
        if (complete != (parallelChecksum != null) || complete != (checksumsMatch != null)) {
            throw new IllegalArgumentException("parallelChecksum and checksumsMatch are known exactly when no block is missing");
        }
        if (complete && (!VectorChecksum.isChecksum(parallelChecksum)
                || checksumsMatch != parallelChecksum.equals(sequentialChecksum))) {
            throw new IllegalArgumentException("checksumsMatch must compare the two checksums");
        }
        if (!Objects.equals(passed, judge(blocksMissing, blocksWithBadChecksum, mismatchedElements, checksumsMatch))) {
            throw new IllegalArgumentException("passed must be false on a known mismatch, null while a block is missing, else true");
        }
    }

    /** The verification for these findings; {@code passed} and {@code checksumsMatch} are derived. */
    public static MatrixVerification of(int rowsExpected, int rowsReceived, int blocksMissing, int blocksWithBadChecksum,
                                        long mismatchedElements, String parallelChecksum, String sequentialChecksum) {
        Boolean match = parallelChecksum == null ? null : parallelChecksum.equals(sequentialChecksum);
        return new MatrixVerification(rowsExpected, rowsReceived, blocksMissing, blocksWithBadChecksum,
                (long) rowsReceived * rowsExpected, mismatchedElements, parallelChecksum, sequentialChecksum, match,
                judge(blocksMissing, blocksWithBadChecksum, mismatchedElements, match));
    }

    private static Boolean judge(int blocksMissing, int blocksWithBadChecksum, long mismatchedElements, Boolean checksumsMatch) {
        if (mismatchedElements > 0 || blocksWithBadChecksum > 0 || Boolean.FALSE.equals(checksumsMatch)) {
            return false;
        }
        return blocksMissing > 0 ? null : true;
    }
}
