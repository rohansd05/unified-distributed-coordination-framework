package com.udcf.dto;

import com.udcf.model.RequestStatus;

import java.util.Map;

/**
 * Live view of one node's executor. Every field is read from the real
 * {@link java.util.concurrent.ThreadPoolExecutor} or from recorded samples —
 * nothing here is synthesised.
 *
 * <p>No dedicated test file: behaviourless data carrier. The values are asserted in
 * ThreadPoolStatsServiceTest.</p>
 *
 * @param activeThreads   threads currently executing a task
 * @param poolSize        threads currently alive in the pool
 * @param queuedRequests  tasks waiting in the bounded queue
 * @param completedTasks  cumulative count reported by the executor
 * @param statusCounts    breakdown of tracked requests by lifecycle state
 */
public record ThreadPoolStats(
        int nodeId,
        int corePoolSize,
        int maxPoolSize,
        int poolSize,
        int activeThreads,
        int largestPoolSize,
        int queuedRequests,
        int queueCapacity,
        int queueRemainingCapacity,
        long completedTasks,
        long totalTasks,
        double requestsPerSecond,
        double averageResponseTimeMillis,
        double p95ResponseTimeMillis,
        int sampleCount,
        Map<RequestStatus, Long> statusCounts
) {
}
