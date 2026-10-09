package com.udcf.modules.election.dto;

import com.udcf.modules.election.ElectionAlgorithm;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/modules/election/elections}: run {@code algorithm} from {@code nodeId}.
 *
 * <p>No dedicated test: a validated record. ElectionControllerTest checks the 400 answers.</p>
 */
public record StartElectionCommand(
        @NotNull ElectionAlgorithm algorithm,
        @NotNull @Min(1) Integer nodeId
) {
}
