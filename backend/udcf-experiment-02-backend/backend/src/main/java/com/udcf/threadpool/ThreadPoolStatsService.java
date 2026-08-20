package com.udcf.threadpool;

import com.udcf.config.ThreadPoolProperties;
import com.udcf.dto.ThreadPoolStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Assembles the live executor snapshot the dashboard renders.
 *
 * <p>Every value is pulled from the running {@link ThreadPoolExecutor} or from recorded
 * samples at the moment of the call. Rule 10 of the project context — no fake or
 * hard-coded distributed metrics — is enforced here in particular, because this is the
 * one class the UI trusts for its numbers.</p>
 */
@Service
public class ThreadPoolStatsService {

    private final ThreadPoolExecutor executor;
    private final RequestRegistry registry;
    private final ThroughputTracker throughputTracker;
    private final ThreadPoolProperties properties;
    private final int nodeId;

    public ThreadPoolStatsService(ThreadPoolExecutor executor,
                                  RequestRegistry registry,
                                  ThroughputTracker throughputTracker,
                                  ThreadPoolProperties properties,
                                  @Value("${udcf.node.id:1}") int nodeId) {
        this.executor = executor;
        this.registry = registry;
        this.throughputTracker = throughputTracker;
        this.properties = properties;
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
                properties.getQueueCapacity(),
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
