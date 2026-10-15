package com.udcf.modules.matrix.dto;

/**
 * Whether the parallel product equals the sequential one, judged only from what arrived (E10a's
 * MatrixVerification, field for field). While any block is missing, parallelChecksum, checksumsMatch and
 * passed are null, never "passed".
 *
 * <p>No dedicated test: a record; tested through MatrixDtoMapperTest.</p>
 *
 * @param mismatchedElements elements of the arrived rows whose bits differ from the sequential product
 * @param passed             false on a known mismatch; null while a block is missing; otherwise true
 */
public record MatrixVerificationDto(int rowsExpected, int rowsReceived, int blocksMissing, int blocksWithBadChecksum,
                                    long elementsCompared, long mismatchedElements, String parallelChecksum,
                                    String sequentialChecksum, Boolean checksumsMatch, Boolean passed) {
}
