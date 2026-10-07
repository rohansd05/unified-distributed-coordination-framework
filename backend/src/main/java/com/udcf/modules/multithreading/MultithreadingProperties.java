package com.udcf.modules.multithreading;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
 */
@Validated
@ConfigurationProperties("udcf.multithreading")
public record MultithreadingProperties(
        @Min(1) int queueCapacity,
        @Min(1) long keepAliveSeconds,
        @NotBlank String threadNamePrefix,
        @Min(1) int metricsWindowSeconds,
        @Min(1) int requestHistorySize
) {
}
