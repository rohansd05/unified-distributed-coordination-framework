package com.udcf.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning knobs for the distributed request thread pool (Experiment 2).
 *
 * <p>Bound from {@code udcf.threadpool.*}. Kept in configuration rather than code so
 * each node instance can be given a different pool shape, which is what makes
 * load-balancing differences between nodes observable in Experiment 6.</p>
 *
 * <p>No dedicated test file: this is a behaviourless configuration holder. Its binding
 * is exercised by ThreadPoolConfigTest, which loads it from real properties.</p>
 */
@ConfigurationProperties(prefix = "udcf.threadpool")
public class ThreadPoolProperties {

    /** Threads kept alive even when idle. */
    private int corePoolSize = 4;

    /** Upper bound on threads; only reached once the queue is full. */
    private int maxPoolSize = 8;

    /** Bounded queue depth. Bounded on purpose so backpressure is demonstrable. */
    private int queueCapacity = 200;

    /** Idle timeout for threads above the core size. */
    private long keepAliveSeconds = 60;

    /** Prefix for worker thread names, so the UI can show which thread ran a request. */
    private String threadNamePrefix = "udcf-worker-";

    /** Sliding window used for throughput and percentile calculations. */
    private int metricsWindowSeconds = 30;

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public long getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    public void setKeepAliveSeconds(long keepAliveSeconds) {
        this.keepAliveSeconds = keepAliveSeconds;
    }

    public String getThreadNamePrefix() {
        return threadNamePrefix;
    }

    public void setThreadNamePrefix(String threadNamePrefix) {
        this.threadNamePrefix = threadNamePrefix;
    }

    public int getMetricsWindowSeconds() {
        return metricsWindowSeconds;
    }

    public void setMetricsWindowSeconds(int metricsWindowSeconds) {
        this.metricsWindowSeconds = metricsWindowSeconds;
    }
}
