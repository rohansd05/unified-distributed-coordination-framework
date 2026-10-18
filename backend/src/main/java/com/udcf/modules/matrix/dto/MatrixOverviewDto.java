package com.udcf.modules.matrix.dto;

import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.mpi.dto.RootSource;
import com.udcf.modules.rmi.dto.CoHostingDto;

import java.util.List;

/**
 * {@code GET /api/modules/matrix}: everything the Experiment 10 page needs to offer a run, and every honesty
 * text. Building it makes no remote call and never starts a service.
 *
 * <p>No dedicated test: a record; tested through MatrixModuleTest and MatrixControllerTest.</p>
 *
 * @param actionInProgress        the guarded action running now; null when not BUSY
 * @param boundName               the name every node binds its MatrixEndpoint under
 * @param defaultRootNodeId       the root a run without a requested root would get now (the leader); null when no leader
 * @param defaultRootSource       LEADER; null while there is no leader (a read never starts an election)
 * @param minimumSizeNow          the smallest size that is not refused now (min-size or the live node count); null
 *                                when every node is down
 * @param processors              the available processors reported by the JVM now
 * @param speedupCeiling          the highest speedup the live nodes could reach; null when every node is down
 * @param speedupBasis            what bounds the speedup with the live nodes; null when every node is down
 * @param experimentTwoSideEffect what this experiment does to Experiment 2's request listeners
 * @param collectiveLatencyBasis  what a scatter or broadcast transfer's latencyNanos measures (Experiment 9)
 * @param coHosting               the simulated aspect: nodes share one process (Simulated badge)
 * @param latest                  the newest kept run; null when none
 */
public record MatrixOverviewDto(
        ModuleStatus status,
        String actionInProgress,
        String boundName,
        MatrixLimitsDto limits,
        List<MatrixNodeDto> participants,
        Integer defaultRootNodeId,
        RootSource defaultRootSource,
        Integer minimumSizeNow,
        int processors,
        Integer speedupCeiling,
        String speedupBasis,
        String algorithmNote,
        String phaseTimeLabel,
        String payloadGenerationNote,
        String fairnessNote,
        String otherActivityNote,
        String experimentTwoSideEffect,
        String firstRunNote,
        String collectiveIdsNote,
        String collectiveLatencyBasis,
        String computeRoundTripBasis,
        String gatherLatencyBasis,
        String payloadSizeBasis,
        String seriesRule,
        List<GlossaryEntryDto> glossary,
        CoHostingDto coHosting,
        MatrixRunDto latest
) {
}
