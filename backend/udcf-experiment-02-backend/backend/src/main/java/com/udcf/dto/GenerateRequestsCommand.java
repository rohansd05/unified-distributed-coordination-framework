package com.udcf.dto;

import com.udcf.model.WorkloadType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for generating a batch of concurrent client requests.
 *
 * <p>Bounds are deliberately generous enough for the "100 concurrent requests" demo in
 * the handoff document but capped so a stray zero cannot wedge the node.</p>
 *
 * <p>No dedicated test file: a validated data carrier. The constraints are asserted
 * through MultithreadingControllerTest, which posts invalid bodies and expects 400.</p>
 */
public record GenerateRequestsCommand(

        @Min(value = 1, message = "count must be at least 1")
        @Max(value = 1000, message = "count must not exceed 1000")
        int count,

        @NotNull(message = "type is required")
        WorkloadType type,

        @Min(value = 1, message = "payloadSize must be at least 1")
        @Max(value = 5000, message = "payloadSize must not exceed 5000")
        int payloadSize
) {
    public static GenerateRequestsCommand of(int count, WorkloadType type, int payloadSize) {
        return new GenerateRequestsCommand(count, type, payloadSize);
    }
}
