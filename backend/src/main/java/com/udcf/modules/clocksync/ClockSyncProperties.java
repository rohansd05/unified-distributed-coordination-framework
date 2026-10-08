package com.udcf.modules.clocksync;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * Configuration properties for Experiment 3 (Clock Synchronization).
 *
 * <p>Bound from {@code udcf.clocksync.*}.</p>
 *
 * @param pollTimeoutMillis      timeout in milliseconds when collecting poll replies or adjust acks
 * @param outlierThresholdMillis maximum permitted absolute deviation from daemon offset before being excluded from averaging
 * @param nodes                  configured simulated drift offsets and rates per node
 */
@Validated
@ConfigurationProperties("udcf.clocksync")
public record ClockSyncProperties(
        @Min(10) long pollTimeoutMillis,
        @Min(0) long outlierThresholdMillis,
        Map<Integer, NodeDriftConfig> nodes
) {

    public record NodeDriftConfig(
            long initialOffsetMillis,
            double driftRateMsPerSec
    ) {
    }

    public ClockSyncProperties {
        if (pollTimeoutMillis < 10) {
            throw new IllegalArgumentException("pollTimeoutMillis must be >= 10");
        }
        if (outlierThresholdMillis < 0) {
            throw new IllegalArgumentException("outlierThresholdMillis must be >= 0");
        }
        nodes = nodes == null ? Map.of() : Map.copyOf(nodes);
    }

    public NodeDriftConfig configFor(int nodeId) {
        return nodes.getOrDefault(nodeId, new NodeDriftConfig(0L, 0.0));
    }
}
