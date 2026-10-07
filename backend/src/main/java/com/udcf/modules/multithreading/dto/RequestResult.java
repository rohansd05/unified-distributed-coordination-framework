package com.udcf.modules.multithreading.dto;

import com.udcf.modules.multithreading.RequestStatus;
import com.udcf.modules.multithreading.WorkloadType;

import java.time.Instant;

/**
 * Immutable snapshot of a request, safe to serialise to the frontend.
 *
 * <p>No dedicated test file: a behaviourless data carrier. It is produced by
 * DistributedRequest.toResult(), which is covered by DistributedRequestTest.</p>
 */
public record RequestResult(
        String id,
        int nodeId,
        WorkloadType type,
        RequestStatus status,
        String threadName,
        Instant submittedAt,
        double queueWaitMillis,
        double processingMillis,
        double totalMillis,
        String resultSummary,
        String errorMessage
) {
}
