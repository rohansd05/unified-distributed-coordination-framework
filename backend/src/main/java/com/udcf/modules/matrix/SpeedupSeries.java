package com.udcf.modules.matrix;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The speedup chart across sizes: one point per matrix size, ascending. Pure; an unknown figure stays
 * null (a point without a speedup is kept and shown as unknown, never as 0).
 */
public record SpeedupSeries(List<Point> points) {

    /**
     * One size's figures, copied from its {@link MatrixTiming}.
     *
     * @param n                  the matrix size
     * @param sequentialNanos    measured
     * @param parallelTotalNanos measured
     * @param communicationNanos derived (scatter + broadcast + gather)
     * @param computePhaseNanos  measured
     * @param speedup            derived, sequential / parallel total
     */
    public record Point(int n, Long sequentialNanos, Long parallelTotalNanos, Long communicationNanos,
                        Long computePhaseNanos, Double speedup) {

        public Point {
            if (n < 1) {
                throw new IllegalArgumentException("n must be >= 1, was " + n);
            }
        }

        public static Point of(MatrixTiming timing) {
            Objects.requireNonNull(timing, "timing must not be null");
            return new Point(timing.n(), timing.sequentialNanos(), timing.parallelTotalNanos(), timing.communicationNanos(),
                    timing.computePhaseNanos(), timing.speedup());
        }
    }

    /** Sorts the points by size; a size given twice is refused. */
    public SpeedupSeries {
        Objects.requireNonNull(points, "points must not be null");
        List<Point> sorted = new ArrayList<>(points);
        sorted.forEach(point -> Objects.requireNonNull(point, "a point must not be null"));
        sorted.sort(Comparator.comparingInt(Point::n));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).n() == sorted.get(i - 1).n()) {
                throw new IllegalArgumentException("size " + sorted.get(i).n() + " appears twice");
            }
        }
        points = List.copyOf(sorted);
    }

    /** The series of these runs, one per size. */
    public static SpeedupSeries of(List<MatrixTiming> timings) {
        Objects.requireNonNull(timings, "timings must not be null");
        return new SpeedupSeries(timings.stream().map(Point::of).toList());
    }

    /** How many sizes have a known speedup (HANDOFF Section 7 asks for at least three). */
    public int sizesWithSpeedup() {
        return (int) points.stream().filter(point -> point.speedup() != null).count();
    }
}
