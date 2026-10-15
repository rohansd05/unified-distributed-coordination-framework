package com.udcf.modules.faulttolerance.dto;

import java.util.List;

/**
 * The no-two-primaries check, run on every live node's own replication role and epoch.
 *
 * <p>No dedicated test: a record; mapped and tested in FaultToleranceDtoMapperTest.</p>
 *
 * @param passed         true exactly when there are no violations
 * @param highestEpoch   the highest epoch any live node knows; null if no node is live
 * @param livePrimaries  live nodes acting as primary, ascending (empty during a failover)
 * @param violationsSeen distinct violations recorded since the last reset (kind, nodes and epoch);
 *                       reads never record one
 */
public record SplitBrainDto(boolean passed, Long highestEpoch, List<Integer> livePrimaries,
                            List<ViolationDto> violations, int violationsSeen) {
}
