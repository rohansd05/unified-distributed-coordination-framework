package com.udcf.modules.mapreduce;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Experiment 7 (MapReduce).
 *
 * <p>Bound from {@code udcf.mapreduce.*}. Values come from YAML only with no code defaults.
 * Registered by {@code @ConfigurationPropertiesScan}.</p>
 *
 * @param taskTimeoutMillis       timeout in milliseconds for task execution on workers (Appendix B: 15000 ms)
 * @param socketReadTimeoutMillis socket read timeout in milliseconds for TCP task transport
 * @param maxRequestBytes         maximum size in bytes for an incoming request or response line
 * @param listenBacklog           socket listen backlog queue length
 * @param workerThreads           worker thread pool size per node (Appendix B: 4)
 * @param queueCapacity           worker task queue capacity (default 32)
 */
@Validated
@ConfigurationProperties("udcf.mapreduce")
public record MapReduceProperties(
        @Min(1) long taskTimeoutMillis,
        @Min(1) long socketReadTimeoutMillis,
        @Min(1) int maxRequestBytes,
        @Min(1) int listenBacklog,
        @Min(1) int workerThreads,
        @Min(1) int queueCapacity
) {

    public MapReduceProperties {
        if (taskTimeoutMillis < 1) {
            throw new IllegalArgumentException("taskTimeoutMillis must be >= 1, was " + taskTimeoutMillis);
        }
        if (socketReadTimeoutMillis < 1) {
            throw new IllegalArgumentException("socketReadTimeoutMillis must be >= 1, was " + socketReadTimeoutMillis);
        }
        if (maxRequestBytes < 1) {
            throw new IllegalArgumentException("maxRequestBytes must be >= 1, was " + maxRequestBytes);
        }
        if (listenBacklog < 1) {
            throw new IllegalArgumentException("listenBacklog must be >= 1, was " + listenBacklog);
        }
        if (workerThreads < 1) {
            throw new IllegalArgumentException("workerThreads must be >= 1, was " + workerThreads);
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be >= 1, was " + queueCapacity);
        }
    }

    /**
     * Convenience constructor with standard defaults for testing without Spring.
     */
    public static MapReduceProperties standard() {
        return new MapReduceProperties(15000, 15000, 4 * 1024 * 1024, 50, 4, 32);
    }
}
