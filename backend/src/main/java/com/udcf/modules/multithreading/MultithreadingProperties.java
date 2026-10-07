package com.udcf.modules.multithreading;

import com.udcf.modules.multithreading.dto.GenerateRequestsCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Experiment 2 executor settings shared by every node, bound from {@code udcf.multithreading.*}.
 *
 * <p>Thread counts are not here: each node's pool is sized by its
 * {@link com.udcf.core.cluster.NodeCapacity} (see {@link NodeExecutorFactory}). No defaults in
 * code, so a missing key fails startup. Registered by {@code @ConfigurationPropertiesScan}.</p>
 *
 * <p>Binding is covered by MultithreadingPropertiesTest.</p>
 *
 * @param queueCapacity        bounded queue depth per node; bounded so backpressure can occur
 * @param keepAliveSeconds     idle timeout for threads above the core size
 * @param threadNamePrefix     base worker-thread prefix; node k's threads add {@code n<k>-}
 * @param metricsWindowSeconds sliding window for throughput and latency statistics
 * @param requestHistorySize   recent requests kept per node for the UI
 * @param readTimeoutMillis    how long the requests service waits for a client's request line
 * @param backpressure         the backpressure demonstration
 */
@Validated
@ConfigurationProperties("udcf.multithreading")
public record MultithreadingProperties(
        @Min(1) int queueCapacity,
        @Min(1) long keepAliveSeconds,
        @NotBlank String threadNamePrefix,
        @Min(1) int metricsWindowSeconds,
        @Min(1) int requestHistorySize,
        @Min(1) int readTimeoutMillis,
        @Valid @NotNull Backpressure backpressure
) {

    /**
     * The backpressure demonstration sends a node {@code threads + queueCapacity +
     * extraRequests} requests as one burst, so at least {@code extraRequests} are rejected.
     *
     * @param extraRequests requests beyond what the node can hold; at least 1
     * @param workload      the work each request does
     * @param payloadSize   work units per request
     */
    public record Backpressure(
            @Min(1) int extraRequests,
            @NotNull WorkloadType workload,
            @Min(GenerateRequestsCommand.MIN_PAYLOAD_SIZE) @Max(GenerateRequestsCommand.MAX_PAYLOAD_SIZE) int payloadSize
    ) {
    }
}
