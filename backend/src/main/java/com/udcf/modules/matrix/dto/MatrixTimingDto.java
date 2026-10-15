package com.udcf.modules.matrix.dto;

/**
 * The times of one run, measured and derived figures kept apart. Null is unknown, never 0. Each node's own
 * compute time (measured on the node) is in its ComputeDto.
 *
 * <p>No dedicated test: a record; tested through MatrixDtoMapperTest and MatrixControllerTest.</p>
 *
 * @param warmupProducts untimed full products before the timed ones (0: the figures include JVM compile effects)
 */
public record MatrixTimingDto(int warmupProducts, Measured measured, Derived derived) {

    /**
     * Figures measured on the root (see the overview's glossary).
     *
     * @param payloadGenerationNanos informational only: never subtracted from any figure
     */
    public record Measured(Long sequentialNanos, Long payloadGenerationNanos, Long scatterNanos, Long broadcastNanos,
                           Long computePhaseNanos, Long gatherNanos, Long parallelTotalNanos) {
    }

    /**
     * Figures computed from the measured ones; never measured themselves.
     *
     * @param otherNanos            can be negative, never clamped
     * @param speedup               sequential / parallel; null unless the run was COMPLETE and matched the
     *                              sequential product
     * @param computeBoundSpeedup   sequential / slowest node compute; never the measured speedup; null as speedup
     * @param speedupCeiling        the smaller of the rank count and the JVM's available processors
     * @param multiplyAdds          n cubed, exact
     * @param speedupWithheldReason set exactly when the run's outcome or verification withholds the speedups
     */
    public record Derived(Long communicationNanos, Long otherNanos, Double communicationShare, Double speedup,
                          Double computeBoundSpeedup, int speedupCeiling, long multiplyAdds, String speedupWithheldReason) {
    }
}
