package com.udcf.modules.matrix;

import com.udcf.modules.matrix.dto.MatrixRunDto;
import com.udcf.modules.matrix.dto.MatrixSeriesDto;
import com.udcf.modules.matrix.dto.MatrixTimingDto;
import com.udcf.modules.mpi.CollectiveOutcome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The speedup series rule ({@link MatrixTexts#SERIES_RULE}): for each configured size, the newest kept run of
 * that size that was COMPLETE and has a speedup (the mapper gives a speedup only to a COMPLETE run that matched
 * the sequential product, so a PARTIAL, FAILED or REFUSED run never makes a point). A size without such a run is
 * a gap, never a point at 0. Built on E10a's {@link SpeedupSeries}. Pure and stateless.
 */
final class MatrixSeries {

    private MatrixSeries() {
    }

    /**
     * @param newestFirst    the kept runs, newest first
     * @param seriesSizes    the configured sizes
     * @param warmupProducts the configured warm-up products (every run uses it)
     */
    static MatrixSeriesDto from(List<MatrixRunDto> newestFirst, List<Integer> seriesSizes, SpeedupBasis basis,
                                int warmupProducts) {
        Objects.requireNonNull(newestFirst, "newestFirst must not be null");
        Objects.requireNonNull(seriesSizes, "seriesSizes must not be null");
        Objects.requireNonNull(basis, "basis must not be null");
        List<SpeedupSeries.Point> points = new ArrayList<>();
        Map<Integer, MatrixRunDto> sources = new HashMap<>();
        List<MatrixSeriesDto.Gap> gaps = new ArrayList<>();
        for (int size : seriesSizes) {
            List<MatrixRunDto> ofSize = newestFirst.stream().filter(run -> run.size() == size).toList();
            Optional<MatrixRunDto> usable = ofSize.stream().filter(MatrixSeries::usable).findFirst();
            if (usable.isPresent()) {
                MatrixTimingDto t = usable.get().timing();
                points.add(new SpeedupSeries.Point(size, t.measured().sequentialNanos(), t.measured().parallelTotalNanos(),
                        t.derived().communicationNanos(), t.measured().computePhaseNanos(), t.derived().speedup()));
                sources.put(size, usable.get());
            } else {
                MatrixRunDto newest = ofSize.isEmpty() ? null : ofSize.get(0);
                gaps.add(new MatrixSeriesDto.Gap(size, newest == null ? null : newest.runId(),
                        newest == null ? null : newest.outcome()));
            }
        }
        SpeedupSeries series = new SpeedupSeries(points);
        List<MatrixSeriesDto.Point> dtoPoints = series.points().stream().map(p -> {
            MatrixRunDto source = sources.get(p.n());
            return new MatrixSeriesDto.Point(p.n(), source.runId(), source.outcome(), source.rankNodeIds().size(),
                    p.sequentialNanos(), p.parallelTotalNanos(), p.communicationNanos(), p.computePhaseNanos(), p.speedup());
        }).toList();
        boolean ranksDiffer = dtoPoints.stream().map(MatrixSeriesDto.Point::ranks).distinct().count() > 1;
        int maxRanks = dtoPoints.stream().mapToInt(MatrixSeriesDto.Point::ranks).max().orElse(0);
        int withSpeedup = series.sizesWithSpeedup();
        return new MatrixSeriesDto(dtoPoints, gaps, withSpeedup, MatrixProperties.MIN_SERIES_SIZES,
                withSpeedup >= MatrixProperties.MIN_SERIES_SIZES, List.copyOf(seriesSizes), ranksDiffer,
                ranksDiffer ? MatrixTexts.RANKS_NOTE : null,
                dtoPoints.isEmpty() ? null : MatrixTexts.speedupBasis(basis, maxRanks, warmupProducts),
                MatrixTexts.SERIES_RULE);
    }

    private static boolean usable(MatrixRunDto run) {
        return run.outcome() == CollectiveOutcome.COMPLETE && run.timing() != null
                && run.timing().derived().speedup() != null;
    }
}
