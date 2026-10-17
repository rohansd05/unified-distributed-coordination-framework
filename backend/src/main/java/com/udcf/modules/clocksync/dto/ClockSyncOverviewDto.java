package com.udcf.modules.clocksync.dto;

import com.udcf.core.module.ModuleStatus;
import java.util.List;

/**
 * Overview payload for Experiment 3 (Clock Synchronization).
 *
 * @param timeDaemonNodeId the leader elected by Experiment 4, which is the daemon of the next
 *                         round; {@code null} when there is no leader yet (reading this never
 *                         starts an election)
 * @param abortedRound     the most recent round when it was aborted; {@code null} when the most
 *                         recent round finished or none ran since the last reset
 */
public record ClockSyncOverviewDto(
        ModuleStatus status,
        String actionInProgress,
        Integer timeDaemonNodeId,
        List<ClockSyncNodeDto> nodes,
        BerkeleyRoundDto latestRound,
        AbortedRoundDto abortedRound,
        ClockSyncLimitsDto limits,
        List<String> notes
) {
}
