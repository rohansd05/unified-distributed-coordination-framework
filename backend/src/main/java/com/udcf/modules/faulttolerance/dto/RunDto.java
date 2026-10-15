package com.udcf.modules.faulttolerance.dto;

import java.util.List;

/**
 * One failover run: its milestones (the timeline), its measurements and its data loss.
 *
 * <p>No dedicated test: a record; mapped and tested in FaultToleranceDtoMapperTest.</p>
 *
 * @param crashSource      ACTION (crashed by this experiment) or OBSERVED (seen as the node went
 *                         down, slightly late); null if the run began with a suspicion
 * @param detectedByNodeId null until detected
 * @param newPrimaryId     null until a node is chosen
 * @param newEpoch         null exactly when {@code newPrimaryId} is
 * @param outcome          IN_PROGRESS, RESTORED, PRIMARY_RETURNED or INTERRUPTED
 * @param milestones       recorded instants only, in time order
 * @param dataLoss         measured when the service was restored; null before that
 */
public record RunDto(long runId, int oldPrimaryId, long oldEpoch, String crashSource, Integer detectedByNodeId,
                     Integer newPrimaryId, Long newEpoch, String outcome, List<MilestoneDto> milestones,
                     MeasurementsDto measurements, DataLossDto dataLoss) {
}
