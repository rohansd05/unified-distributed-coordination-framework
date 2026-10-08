package com.udcf.modules.clocksync;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;
import java.util.Objects;

/**
 * Configuration properties for Experiment 3 (Clock Synchronization).
 *
 * <p>Bound from {@code udcf.clocksync.*}. Values come from YAML only with no code defaults.
 * Registered by {@code @ConfigurationPropertiesScan}.</p>
 *
 * @param pollTimeoutMillis        timeout in milliseconds when collecting poll replies or adjust acks
 * @param outlierThresholdMillis   maximum permitted absolute deviation from daemon offset before being excluded from averaging
 * @param defaultTrafficSeconds    default duration in seconds for random traffic bursts
 * @param maxTrafficSeconds        maximum permitted duration in seconds for random traffic bursts
 * @param defaultMessagesPerSecond default rate of messages per second for random traffic bursts
 * @param maxMessagesPerSecond     maximum permitted rate of messages per second for random traffic bursts
 * @param retainedEventsCapacity   maximum number of events retained in the bounded event log before dropping oldest
 * @param nodes                    configured simulated drift offsets and rates per node
 */
@Validated
@ConfigurationProperties("udcf.clocksync")
public record ClockSyncProperties(
        @Min(10) long pollTimeoutMillis,
        @Min(0) long outlierThresholdMillis,
        @Min(1) int defaultTrafficSeconds,
        @Min(1) int maxTrafficSeconds,
        @Min(1) int defaultMessagesPerSecond,
        @Min(1) int maxMessagesPerSecond,
        @Min(10) int retainedEventsCapacity,
        @NotNull Map<Integer, NodeDriftConfig> nodes
) {

    public record NodeDriftConfig(
            long initialOffsetMillis,
            double driftRateMsPerSec
    ) {
    }

    public ClockSyncProperties {
        if (pollTimeoutMillis < 10) {
            throw new IllegalArgumentException("pollTimeoutMillis must be >= 10, was " + pollTimeoutMillis);
        }
        if (outlierThresholdMillis < 0) {
            throw new IllegalArgumentException("outlierThresholdMillis must be >= 0, was " + outlierThresholdMillis);
        }
        if (defaultTrafficSeconds < 1) {
            throw new IllegalArgumentException("defaultTrafficSeconds must be >= 1, was " + defaultTrafficSeconds);
        }
        if (maxTrafficSeconds < defaultTrafficSeconds) {
            throw new IllegalArgumentException("maxTrafficSeconds must be >= defaultTrafficSeconds ("
                    + maxTrafficSeconds + " < " + defaultTrafficSeconds + ")");
        }
        if (defaultMessagesPerSecond < 1) {
            throw new IllegalArgumentException("defaultMessagesPerSecond must be >= 1, was " + defaultMessagesPerSecond);
        }
        if (maxMessagesPerSecond < defaultMessagesPerSecond) {
            throw new IllegalArgumentException("maxMessagesPerSecond must be >= defaultMessagesPerSecond ("
                    + maxMessagesPerSecond + " < " + defaultMessagesPerSecond + ")");
        }
        if (retainedEventsCapacity < 10) {
            throw new IllegalArgumentException("retainedEventsCapacity must be >= 10, was " + retainedEventsCapacity);
        }
        Objects.requireNonNull(nodes, "nodes must not be null");
        nodes = Map.copyOf(nodes);
    }

    public NodeDriftConfig configFor(int nodeId) {
        NodeDriftConfig cfg = nodes.get(nodeId);
        if (cfg == null) {
            throw new IllegalArgumentException("No drift configuration found for node " + nodeId);
        }
        return cfg;
    }
}
