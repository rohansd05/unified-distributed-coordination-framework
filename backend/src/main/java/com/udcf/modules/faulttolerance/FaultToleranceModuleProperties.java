package com.udcf.modules.faulttolerance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Objects;

/**
 * Settings of the Experiment 8 module and its API (step E8c), bound from
 * {@code udcf.faulttolerance.*} beside {@link FaultToleranceProperties} (the E8b settings on the
 * same prefix; each record binds only its own keys). No defaults in code, so a missing key fails
 * startup. Registered by {@code @ConfigurationPropertiesScan}.
 *
 * <p>Covered by FaultToleranceModulePropertiesTest.</p>
 *
 * @param stream the client update stream
 */
@Validated
@ConfigurationProperties("udcf.faulttolerance")
public record FaultToleranceModuleProperties(@NotNull @Valid Stream stream) {

    /**
     * The update stream sends one update at a time.
     *
     * <p><b>Sizing.</b> Experiment 5 publishes about 10 events per update on 5 nodes (6 on 3), so
     * {@code maxUpdates}, the budget of one reset session, keeps them inside the event buffer.</p>
     *
     * @param defaultRatePerSecond the rate the page offers first
     * @param maxRatePerSecond     the highest rate a stream may ask for
     * @param maxUpdates           updates all streams may send between two resets
     */
    public record Stream(@Min(1) int defaultRatePerSecond, @Min(1) int maxRatePerSecond, @Min(1) int maxUpdates) {

        public Stream {
            if (defaultRatePerSecond < 1) {
                throw new IllegalArgumentException("defaultRatePerSecond must be >= 1, was " + defaultRatePerSecond);
            }
            if (maxRatePerSecond < defaultRatePerSecond) {
                throw new IllegalArgumentException("maxRatePerSecond (" + maxRatePerSecond
                        + ") must be at least defaultRatePerSecond (" + defaultRatePerSecond + ")");
            }
            if (maxUpdates < 1) {
                throw new IllegalArgumentException("maxUpdates must be >= 1, was " + maxUpdates);
            }
        }
    }

    public FaultToleranceModuleProperties {
        Objects.requireNonNull(stream, "stream must not be null");
    }
}
