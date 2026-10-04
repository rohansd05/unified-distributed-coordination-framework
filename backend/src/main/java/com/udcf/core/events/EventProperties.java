package com.udcf.core.events;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Sizes for the cluster event bus, bound from {@code udcf.events.*}.
 *
 * <p>No defaults in code: a missing key fails startup rather than silently using a
 * hard-coded size. Registered by {@code @ConfigurationPropertiesScan}, like every other
 * properties class.</p>
 *
 * <p>No dedicated test file: this is a behaviourless configuration holder. Its binding and
 * validation are exercised by EventConfigTest.</p>
 *
 * @param bufferSize        events kept in the ring buffer (5000 local, 1500 public)
 * @param dispatchQueueSize events waiting for subscribers before notifications are dropped
 */
@Validated
@ConfigurationProperties("udcf.events")
public record EventProperties(
        @Min(1) int bufferSize,
        @Min(1) int dispatchQueueSize
) {
}
