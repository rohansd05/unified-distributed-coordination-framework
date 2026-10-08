package com.udcf.modules.replication.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body of {@code POST /api/modules/replication/anti-entropy}: push the primary's whole
 * store to one backup.
 *
 * <p>No dedicated test: a record; validation is tested in ReplicationControllerTest.</p>
 */
public record AntiEntropyCommand(
        @NotNull(message = "targetNodeId is required") @Min(1) Integer targetNodeId
) {
}
