package com.udcf.modules.clocksync.dto;

import com.udcf.core.module.ModuleStatus;
import java.util.List;

/**
 * Overview payload for Experiment 3 (Clock Synchronization).
 */
public record ClockSyncOverviewDto(
        ModuleStatus status,
        String actionInProgress,
        Integer timeDaemonNodeId,
        List<ClockSyncNodeDto> nodes,
        BerkeleyRoundDto latestRound,
        ClockSyncLimitsDto limits,
        List<String> notes
) {
}
