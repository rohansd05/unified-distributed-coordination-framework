package com.udcf.modules.rmi.dto;

import com.udcf.core.module.ModuleStatus;

import java.util.List;

/**
 * {@code GET /api/modules/rmi}: everything the Experiment 1 page needs to offer a call. Building
 * it makes no remote call and never starts a service.
 *
 * <p>No dedicated test: a record; tested through RmiModuleTest and RmiControllerTest.</p>
 *
 * @param actionInProgress the guarded action running now; null when not BUSY
 * @param boundName        the name every node binds its endpoint under
 * @param payloadSizeBasis what argumentBytes and resultBytes measure
 * @param lookupBasis      what lookupNanos measures
 * @param coHosting        the simulated aspect: nodes share one process (Simulated badge)
 * @param comparisonNote   how to read the local-versus-remote comparison
 * @param failureKinds     every failure kind with its description, in declaration order
 * @param latest           the newest kept call or comparison; null when none
 */
public record RmiOverviewDto(
        ModuleStatus status,
        String actionInProgress,
        String boundName,
        List<RmiNodeDto> nodes,
        List<MethodDto> methods,
        RmiLimitsDto limits,
        String payloadSizeBasis,
        String lookupBasis,
        CoHostingDto coHosting,
        String comparisonNote,
        List<FailureKindDto> failureKinds,
        HistoryEntryDto latest
) {
}
