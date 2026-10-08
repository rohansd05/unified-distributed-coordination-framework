package com.udcf.modules.replication.dto;

import com.udcf.modules.replication.DataItem;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body of {@code POST /api/modules/replication/stale-injections}: deliver to one backup
 * a version of {@code key} one Lamport tick older than the primary's, carrying {@code staleValue}.
 *
 * <p>No dedicated test: a record; validation is tested in ReplicationControllerTest.</p>
 */
public record InjectStaleCommand(
        @NotNull(message = "backupNodeId is required") @Min(1) Integer backupNodeId,
        @NotBlank(message = "key is required") @Size(max = DataItem.MAX_KEY_LENGTH) String key,
        @NotNull(message = "staleValue is required") @Size(max = DataItem.MAX_VALUE_LENGTH) String staleValue
) {
}
