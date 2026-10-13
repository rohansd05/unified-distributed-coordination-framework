package com.udcf.modules.matrix;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Objects;

/**
 * The settings of a parallel matrix run (step E10b), bound from the same {@code udcf.matrix.*} block
 * as {@link MatrixProperties}. A separate record, so the E10a properties stay unchanged (as
 * RmiTransportProperties beside RmiProperties).
 *
 * <p>No defaults in code, so a missing key fails startup ({@code warmupProducts} is an Integer for that
 * reason: 0 is a valid value). Registered by {@code @ConfigurationPropertiesScan}. Binding is covered by
 * MatrixRunPropertiesTest.</p>
 *
 * @param warmupProducts       untimed full products at the run's size before the timed sequential product
 *                             (JIT warm-up); 0 allowed
 * @param computeTimeoutMillis longest a node (and the root, for its own block) waits for its multiply on its
 *                             Experiment 2 executor, queue wait included; must be below
 *                             {@code udcf.rmi.call-timeout-millis} (checked by MatrixEndpointExport), so a node
 *                             always answers before the caller's read timeout
 * @param resultStoreSize      computed blocks of C each node keeps by run id until fetched; the oldest is dropped
 */
@Validated
@ConfigurationProperties("udcf.matrix")
public record MatrixRunProperties(@NotNull @Min(0) Integer warmupProducts, @Min(1) int computeTimeoutMillis,
                                  @Min(1) int resultStoreSize) {

    public MatrixRunProperties {
        Objects.requireNonNull(warmupProducts, "warmupProducts must not be null");
        if (warmupProducts < 0) {
            throw new IllegalArgumentException("warmupProducts must be >= 0, was " + warmupProducts);
        }
        if (computeTimeoutMillis < 1) {
            throw new IllegalArgumentException("computeTimeoutMillis must be >= 1, was " + computeTimeoutMillis);
        }
        if (resultStoreSize < 1) {
            throw new IllegalArgumentException("resultStoreSize must be >= 1, was " + resultStoreSize);
        }
    }
}
