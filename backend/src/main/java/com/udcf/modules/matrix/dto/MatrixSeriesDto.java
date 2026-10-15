package com.udcf.modules.matrix.dto;

import com.udcf.modules.mpi.CollectiveOutcome;

import java.util.List;

/**
 * {@code GET /api/modules/matrix/series}: the speedup chart across the configured sizes, built from the kept
 * runs by the rule in {@code rule}. A size without a usable run is a gap, never a point at 0.
 *
 * <p>No dedicated test: a record; tested through MatrixSeriesTest and MatrixControllerTest.</p>
 *
 * @param points           one per configured size with a usable run, ascending
 * @param gaps             the configured sizes without one, ascending
 * @param sizesWithSpeedup points with a known speedup
 * @param sizesRequired    how many HANDOFF Section 7 asks for (3)
 * @param done             sizesWithSpeedup >= sizesRequired
 * @param ranksDiffer      the points were measured with different numbers of ranks
 * @param ranksNote        set exactly when ranksDiffer
 * @param basis            what bounds the speedups, for the largest rank count among the points; null without points
 */
public record MatrixSeriesDto(List<Point> points, List<Gap> gaps, int sizesWithSpeedup, int sizesRequired, boolean done,
                              List<Integer> seriesSizes, boolean ranksDiffer, String ranksNote, String basis, String rule) {

    /** One size's figures, copied from its source run (E10a's SpeedupSeries.Point plus where it came from). */
    public record Point(int size, long sourceRunId, CollectiveOutcome sourceOutcome, int ranks, Long sequentialNanos,
                        Long parallelTotalNanos, Long communicationNanos, Long computePhaseNanos, Double speedup) {
    }

    /**
     * A configured size without a usable run.
     *
     * @param newestRunId   the newest kept run of that size; null when none is kept
     * @param newestOutcome its outcome; null when none is kept
     */
    public record Gap(int size, Long newestRunId, CollectiveOutcome newestOutcome) {
    }
}
