package com.udcf.modules.election.dto;

import java.util.List;

/**
 * {@code GET /api/modules/election}: everything the Experiment 4 page shows.
 *
 * <p>No dedicated test: a plain record. ElectionModuleTest and ElectionControllerTest check it.</p>
 *
 * @param status          the module status (IDLE, RUNNING, BUSY, ERROR)
 * @param leaderId        the node holding the cluster LEADER role, or {@code null}
 * @param servicesStarted whether the election services have been started (on the first election)
 * @param currentRound    the open round, or {@code null}
 * @param lastRound       the last finished round, or {@code null}
 */
public record ElectionOverviewDto(
        String status,
        Integer leaderId,
        boolean servicesStarted,
        List<ElectionNodeDto> nodes,
        ConsensusDto consensus,
        ElectionRoundDto currentRound,
        ElectionRoundDto lastRound,
        ElectionSettingsDto settings
) {
}
