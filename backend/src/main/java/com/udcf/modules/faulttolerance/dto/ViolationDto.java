package com.udcf.modules.faulttolerance.dto;

import java.util.List;

/**
 * One break of the single-primary rule.
 *
 * <p>No dedicated test: a record; mapped and tested in FaultToleranceDtoMapperTest.</p>
 *
 * @param kind    DUPLICATE_PRIMARY or STALE_PRIMARY
 * @param nodeIds the primaries involved, ascending
 */
public record ViolationDto(String kind, List<Integer> nodeIds, long epoch, long highestEpoch, String message) {
}
