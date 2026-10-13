package com.udcf.modules.mpi.dto;

import com.udcf.modules.mpi.RefusalReason;

/**
 * Why a collective was refused before anything was sent.
 *
 * <p>No dedicated test: a record; tested through MpiDtoMapperTest.</p>
 */
public record RefusalDto(RefusalReason reason, String message) {
}
