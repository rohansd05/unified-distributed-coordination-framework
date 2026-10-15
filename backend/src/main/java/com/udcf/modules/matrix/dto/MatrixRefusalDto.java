package com.udcf.modules.matrix.dto;

import com.udcf.modules.matrix.MatrixRefusal;

/**
 * Why a run was refused before anything was generated or sent.
 *
 * <p>No dedicated test: a record; tested through MatrixDtoMapperTest.</p>
 */
public record MatrixRefusalDto(MatrixRefusal.Reason reason, String message) {
}
