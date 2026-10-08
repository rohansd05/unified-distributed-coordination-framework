package com.udcf.modules.replication.dto;

import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.modules.replication.DataItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body of {@code POST /api/modules/replication/writes}. The module also applies the
 * {@link DataItem} rules (no control or line-break characters) and answers 400 if they fail.
 *
 * <p>No dedicated test: a record; validation is tested in ReplicationControllerTest.</p>
 *
 * @param key   1 to 64 characters, not blank; {@code ;} and {@code ~} are allowed
 * @param value 0 to 1024 characters (empty allowed)
 * @param model SYNCHRONOUS or ASYNCHRONOUS
 */
public record WriteCommand(
        @NotBlank(message = "key is required") @Size(max = DataItem.MAX_KEY_LENGTH) String key,
        @NotNull(message = "value is required") @Size(max = DataItem.MAX_VALUE_LENGTH) String value,
        @NotNull(message = "model is required") ConsistencyModel model
) {
}
