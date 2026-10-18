package com.udcf.modules.mpi.dto;

import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.rmi.dto.CoHostingDto;

import java.util.List;

/**
 * {@code GET /api/modules/mpi}: everything the Experiment 9 page needs to offer a collective.
 * Building it makes no remote call and never starts a service.
 *
 * <p>No dedicated test: a record; tested through MpiModuleTest and MpiControllerTest.</p>
 *
 * @param actionInProgress  the guarded action running now; null when not BUSY
 * @param boundName         the name every node binds its CollectiveEndpoint under
 * @param participants      every cluster node (all take part; a crashed one is skipped at the start)
 * @param defaultRootNodeId the root a collective without a requested root would get now (the leader); null when no
 *                          leader
 * @param defaultRootSource LEADER; null while there is no leader (a read never starts an election)
 * @param latencyBasis      what a transfer's latencyNanos measures
 * @param payloadSizeBasis  what requestBytes and replyBytes measure
 * @param algorithmNote     how the collectives are carried out (linear, from the root)
 * @param coHosting         the simulated aspect: nodes share one process (Simulated badge)
 * @param latest            the newest kept collective; null when none
 */
public record MpiOverviewDto(
        ModuleStatus status,
        String actionInProgress,
        String boundName,
        List<OperationDto> operations,
        MpiLimitsDto limits,
        List<MpiNodeDto> participants,
        Integer defaultRootNodeId,
        RootSource defaultRootSource,
        String latencyBasis,
        String payloadSizeBasis,
        String algorithmNote,
        CoHostingDto coHosting,
        CollectiveDto latest
) {
}
