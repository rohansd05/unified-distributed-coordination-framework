package com.udcf.modules.matrix;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Experiment 10 settings, bound from {@code udcf.matrix.*}.
 *
 * <p>No defaults in code, so a missing key fails startup. Registered by
 * {@code @ConfigurationPropertiesScan}. There is no maximum size here: it is derived from
 * {@code udcf.mpi.max-payload-elements} by {@link MatrixLimits}, which also checks the series
 * against it. Binding is covered by MatrixPropertiesTest.</p>
 *
 * @param minSize     smallest matrix size N a run accepts (at least 1)
 * @param seriesSizes the sizes of the speedup series: at least three, strictly ascending, each at least
 *                    {@code minSize}; smaller in the public profile
 */
@Validated
@ConfigurationProperties("udcf.matrix")
public record MatrixProperties(@Min(1) int minSize, List<Integer> seriesSizes) {

    /** The fewest sizes a speedup series may have (HANDOFF Section 7: speedup for at least three sizes). */
    public static final int MIN_SERIES_SIZES = 3;

    public MatrixProperties {
        if (minSize < 1) {
            throw new IllegalArgumentException("minSize must be >= 1, was " + minSize);
        }
        if (seriesSizes == null || seriesSizes.size() < MIN_SERIES_SIZES) {
            throw new IllegalArgumentException("seriesSizes must have at least " + MIN_SERIES_SIZES + " sizes, was " + seriesSizes);
        }
        seriesSizes = List.copyOf(seriesSizes);
        int previous = 0;
        for (int size : seriesSizes) {
            if (size < minSize) {
                throw new IllegalArgumentException("seriesSizes must each be >= minSize " + minSize + ", was " + seriesSizes);
            }
            if (size <= previous) {
                throw new IllegalArgumentException("seriesSizes must be strictly ascending, was " + seriesSizes);
            }
            previous = size;
        }
    }
}
