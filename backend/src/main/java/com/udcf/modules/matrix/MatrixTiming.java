package com.udcf.modules.matrix;

import java.util.List;
import java.util.Objects;

/**
 * The times of one matrix run. Every measured figure is nullable: null means unknown (not measured,
 * or its part did not happen), never 0. Derived figures are computed here and are null whenever an
 * input is null.
 *
 * <p><b>Measured.</b></p>
 * <ul>
 *   <li>{@code sequentialNanos}: on the root, around the multiply of the whole matrix only, run
 *       separately from the parallel run.</li>
 *   <li>{@code scatterNanos}, {@code broadcastNanos}: on the root, Experiment 9's {@code totalNanos} of
 *       the scatter of A and the broadcast of B (just before the first remote transfer to just after the
 *       last). It includes the root's own payload sizing and checksum work between the transfers, not
 *       only time on the wire.</li>
 *   <li>{@code computePhaseNanos}: on the root, from just before the first compute is dispatched to
 *       just after the last block (remote or the root's own) is computed.</li>
 *   <li>{@code gatherNanos}: on the root, from just before the first block of C is fetched to just after
 *       the last.</li>
 *   <li>{@code parallelTotalNanos}: on the root, from just before the scatter to just after the last
 *       gathered block; one interval, measured separately, not a sum.</li>
 *   <li>{@link RankCompute#computeNanos}: measured on the node, around that rank's multiply only. All
 *       nodes run in one process, so the node and the root read the same {@code System.nanoTime}.</li>
 * </ul>
 * <p><b>Derived.</b> {@link #communicationNanos}, {@link #otherNanos}, {@link #communicationShare},
 * {@link #speedup}, {@link #computeBoundSpeedup} and {@link #multiplyAdds}.</p>
 *
 * @param n                  the matrix size
 * @param warmupProducts     untimed products done before the timed ones, to let the JIT compile the multiply
 *                           (0: none, so the figures include JVM compile effects)
 * @param sequentialNanos    measured, see above
 * @param scatterNanos       measured, see above
 * @param broadcastNanos     measured, see above
 * @param computePhaseNanos  measured, see above; this is the computation time of the parallel run
 * @param gatherNanos        measured, see above
 * @param parallelTotalNanos measured, see above
 * @param rankComputes       one entry per rank, rank order
 */
public record MatrixTiming(int n, int warmupProducts, Long sequentialNanos, Long scatterNanos, Long broadcastNanos,
                           Long computePhaseNanos, Long gatherNanos, Long parallelTotalNanos,
                           List<RankCompute> rankComputes) {

    /**
     * One rank's multiply.
     *
     * @param rank         the rank
     * @param nodeId       the node of that rank
     * @param rowCount     the rows it multiplied
     * @param computeNanos measured on the node, around the multiply only; null when that compute failed or
     *                     was not attempted
     */
    public record RankCompute(int rank, int nodeId, int rowCount, Long computeNanos) {

        public RankCompute {
            if (rank < 0 || nodeId < 1 || rowCount < 1) {
                throw new IllegalArgumentException("a rank compute needs rank >= 0, nodeId >= 1 and rowCount >= 1, was "
                        + rank + ", " + nodeId + ", " + rowCount);
            }
            requireNotNegative("computeNanos", computeNanos);
        }
    }

    public MatrixTiming {
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1, was " + n);
        }
        if (warmupProducts < 0) {
            throw new IllegalArgumentException("warmupProducts must be >= 0, was " + warmupProducts);
        }
        requireNotNegative("sequentialNanos", sequentialNanos);
        requireNotNegative("scatterNanos", scatterNanos);
        requireNotNegative("broadcastNanos", broadcastNanos);
        requireNotNegative("computePhaseNanos", computePhaseNanos);
        requireNotNegative("gatherNanos", gatherNanos);
        requireNotNegative("parallelTotalNanos", parallelTotalNanos);
        rankComputes = List.copyOf(Objects.requireNonNull(rankComputes, "rankComputes must not be null"));
        for (int i = 0; i < rankComputes.size(); i++) {
            if (rankComputes.get(i).rank() != i) {
                throw new IllegalArgumentException("rankComputes must be in rank order from 0; entry " + i + " is rank "
                        + rankComputes.get(i).rank());
            }
        }
    }

    /** Derived: scatter + broadcast + gather; null if any of them is unknown. */
    public Long communicationNanos() {
        if (scatterNanos == null || broadcastNanos == null || gatherNanos == null) {
            return null;
        }
        return scatterNanos + broadcastNanos + gatherNanos;
    }

    /**
     * Derived: the total minus communication minus the compute phase, that is the root's bookkeeping
     * between the phases. It can be negative (the phases are measured separately from the total) and is
     * reported as it is, never clamped; null if any input is unknown.
     */
    public Long otherNanos() {
        Long communication = communicationNanos();
        if (parallelTotalNanos == null || communication == null || computePhaseNanos == null) {
            return null;
        }
        return parallelTotalNanos - communication - computePhaseNanos;
    }

    /** Derived: communication / parallel total; null if either is unknown or the total is 0. */
    public Double communicationShare() {
        return ratio(communicationNanos(), parallelTotalNanos);
    }

    /** Derived: sequential / parallel total, the measured speedup; null if either is unknown or the total is 0. */
    public Double speedup() {
        return ratio(sequentialNanos, parallelTotalNanos);
    }

    /**
     * Derived, never the measured speedup: sequential / the slowest rank's compute (measured on the
     * node), that is what the multiply alone would allow if communication cost nothing. Null if the
     * sequential time or any rank's compute is unknown, there is no rank, or the slowest compute is 0.
     */
    public Double computeBoundSpeedup() {
        if (rankComputes.isEmpty() || rankComputes.stream().anyMatch(r -> r.computeNanos() == null)) {
            return null;
        }
        long slowest = rankComputes.stream().mapToLong(RankCompute::computeNanos).max().orElseThrow();
        return ratio(sequentialNanos, slowest);
    }

    /** Derived, exact: the multiply-add operations of an n x n product, n cubed. */
    public long multiplyAdds() {
        return multiplyAdds(n);
    }

    /** n cubed, exactly. */
    public static long multiplyAdds(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1, was " + n);
        }
        return Math.multiplyExact(Math.multiplyExact((long) n, n), n);
    }

    private static Double ratio(Long numerator, Long denominator) {
        if (numerator == null || denominator == null || denominator == 0) {
            return null;
        }
        return (double) numerator / denominator;
    }

    private static void requireNotNegative(String name, Long nanos) {
        if (nanos != null && nanos < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, was " + nanos);
        }
    }
}
