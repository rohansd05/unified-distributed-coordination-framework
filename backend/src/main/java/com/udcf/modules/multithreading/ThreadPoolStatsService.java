package com.udcf.modules.multithreading;

import com.udcf.modules.multithreading.dto.ThreadPoolStats;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Assembles the live executor snapshot the page renders for one node.
 *
 * <p>Every value is pulled from the running {@link ThreadPoolExecutor} or from recorded
 * samples at the moment of the call. Decision R7 — no fabricated metrics — is enforced
 * here in particular, because this is the one class the UI trusts for its numbers.</p>
 */
public class ThreadPoolStatsService {

    private final ThreadPoolExecutor executor;
    private final RequestRegistry registry;
    private final ThroughputTracker throughputTracker;
    private final int queueCapacity;
    private final int nodeId;

    public ThreadPoolStatsService(ThreadPoolExecutor executor,
                                  RequestRegistry registry,
                                  ThroughputTracker throughputTracker,
                                  int queueCapacity,
                                  int nodeId) {
        this.executor = executor;
        this.registry = registry;
        this.throughputTracker = throughputTracker;
        this.queueCapacity = queueCapacity;
        this.nodeId = nodeId;
    }

    public ThreadPoolStats snapshot() {
        int queued = executor.getQueue().size();
        int remaining = executor.getQueue().remainingCapacity();

        return new ThreadPoolStats(
                nodeId,
                executor.getCorePoolSize(),
                executor.getMaximumPoolSize(),
                executor.getPoolSize(),
                executor.getActiveCount(),
                executor.getLargestPoolSize(),
                queued,
                queueCapacity,
                remaining,
                executor.getCompletedTaskCount(),
                executor.getTaskCount(),
                throughputTracker.requestsPerSecond(),
                throughputTracker.averageResponseTimeMillis(),
                throughputTracker.p95ResponseTimeMillis(),
                throughputTracker.sampleCount(),
                registry.countByStatus()
        );
    }

    /** Clears request history and latency samples. Does not touch the executor itself. */
    public void reset() {
        registry.clear();
        throughputTracker.clear();
    }
}
