package com.udcf.modules.matrix;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The settings of the Experiment 10 module (step E10c), bound from the same {@code udcf.matrix.*} block as
 * {@link MatrixProperties} and {@link MatrixRunProperties}. A separate record, so the E10a and E10b records
 * stay unchanged.
 *
 * <p>No defaults in code, so a missing key fails startup. Registered by {@code @ConfigurationPropertiesScan}.
 * {@link MatrixModule} also refuses a history smaller than the number of series sizes. Binding is covered by
 * MatrixModulePropertiesTest.</p>
 *
 * @param historySize runs the module keeps for the page and the speedup series, newest first
 */
@Validated
@ConfigurationProperties("udcf.matrix")
public record MatrixModuleProperties(@Min(1) int historySize) {

    public MatrixModuleProperties {
        if (historySize < 1) {
            throw new IllegalArgumentException("historySize must be >= 1, was " + historySize);
        }
    }
}
