package com.udcf.modules.mpi.dto;

/**
 * What a collective proved from the data that actually arrived; an unknown is null, never passed.
 *
 * <p>No dedicated test: a record; tested through MpiDtoMapperTest.</p>
 *
 * @param passed             true: every check made and matched; false: a mismatch; null: something unknown or
 *                           nothing to check
 * @param wholeVectorMatches scatter: the verified chunks tile the vector; gather: the assembled vector equals the
 *                           independently computed one; null for a broadcast or while a part is missing
 */
public record VerificationDto(Boolean passed, int checked, int matched, int mismatched, int unknown,
                              Boolean wholeVectorMatches) {
}
