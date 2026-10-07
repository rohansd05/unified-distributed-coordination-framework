package com.udcf.modules.multithreading.dto;

import com.udcf.modules.multithreading.WorkloadType;
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

        @Min(value = MIN_COUNT, message = "count must be at least 1")
        @Max(value = MAX_COUNT, message = "count must not exceed 1000")
        int count,

        @NotNull(message = "type is required")
        WorkloadType type,

        @Min(value = MIN_PAYLOAD_SIZE, message = "payloadSize must be at least 1")
        @Max(value = MAX_PAYLOAD_SIZE, message = "payloadSize must not exceed 5000")
        int payloadSize
) {
    /** Batch and payload limits from docs/HANDOFF.md Appendix B (Exp 2); the TCP protocol uses the same payload limits. */
    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 1000;
    public static final int MIN_PAYLOAD_SIZE = 1;
    public static final int MAX_PAYLOAD_SIZE = 5000;

    public static GenerateRequestsCommand of(int count, WorkloadType type, int payloadSize) {
        return new GenerateRequestsCommand(count, type, payloadSize);
    }
}
