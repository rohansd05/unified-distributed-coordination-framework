package com.udcf.modules.matrix;

import com.udcf.modules.matrix.dto.MatrixRunDto;
import com.udcf.modules.matrix.dto.MatrixSeriesDto;
import com.udcf.modules.matrix.dto.MatrixTimingDto;
import com.udcf.modules.mpi.CollectiveOutcome;
import com.udcf.modules.mpi.dto.RootSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The series rule on minimal run entries (only the fields the rule reads are filled; the mapper's gating of the
 * speedup is tested in MatrixDtoMapperTest): newest usable run per configured size, gaps never 0, done at three.
 */
class MatrixSeriesTest {

    private static final List<Integer> SIZES = List.of(50, 100, 200, 316);

    /** A kept run of {@code size}; speedup null means withheld or unknown; ranks node ids 1..ranks. */
    private static MatrixRunDto run(long id, int size, CollectiveOutcome outcome, Double speedup, int ranks) {
        MatrixTimingDto timing = outcome == CollectiveOutcome.REFUSED ? null : new MatrixTimingDto(1,
                new MatrixTimingDto.Measured(1000L * id, 10L, 20L, 30L, 40L, 50L, 500L * id),
                new MatrixTimingDto.Derived(100L, 5L, 0.2, speedup, null, ranks, (long) size * size * size,
                        speedup == null ? "withheld" : null));
        List<Integer> nodes = outcome == CollectiveOutcome.REFUSED ? List.of()
                : java.util.stream.IntStream.rangeClosed(1, ranks).boxed().toList();
        return new MatrixRunDto(id, Instant.EPOCH, size, 1, 1, RootSource.LOWEST_LIVE_ID, null, nodes, List.of(), outcome,
                null, 10_000, null, null, List.of(), List.of(), timing, null, null, null, null, null, null);
    }

    private static MatrixSeriesDto series(MatrixRunDto... newestFirst) {
        return MatrixSeries.from(List.of(newestFirst), SIZES, MatrixTestResults.BASIS, 1);
    }

    @Test
    @DisplayName("one point per size from the newest usable run, ascending, naming its run, outcome and ranks")
    void newestUsablePerSize() {
        MatrixSeriesDto dto = series(run(4, 100, CollectiveOutcome.COMPLETE, 1.4, 3), run(3, 50, CollectiveOutcome.COMPLETE, 0.6, 3),
                run(2, 100, CollectiveOutcome.COMPLETE, 1.1, 3), run(1, 200, CollectiveOutcome.COMPLETE, 1.9, 3));

        assertThat(dto.points()).extracting(MatrixSeriesDto.Point::size).containsExactly(50, 100, 200);
        MatrixSeriesDto.Point hundred = dto.points().get(1);
        assertThat(hundred.sourceRunId()).isEqualTo(4);
        assertThat(hundred.sourceOutcome()).isEqualTo(CollectiveOutcome.COMPLETE);
        assertThat(hundred.ranks()).isEqualTo(3);
        assertThat(hundred.speedup()).isEqualTo(1.4);
        assertThat(hundred.sequentialNanos()).isEqualTo(4000);
        assertThat(hundred.parallelTotalNanos()).isEqualTo(2000);
        assertThat(hundred.communicationNanos()).isEqualTo(100);
        assertThat(hundred.computePhaseNanos()).isEqualTo(40);
        assertThat(dto.gaps()).extracting(MatrixSeriesDto.Gap::size).containsExactly(316);
    }

    @Test
    @DisplayName("PARTIAL, FAILED, REFUSED and a run without speedup never make a point; the gap names the newest run")
    void unusableRunsAreGaps() {
        MatrixSeriesDto dto = series(run(4, 50, CollectiveOutcome.PARTIAL, null, 3), run(3, 100, CollectiveOutcome.FAILED, null, 3),
                run(2, 200, CollectiveOutcome.REFUSED, null, 3), run(1, 316, CollectiveOutcome.COMPLETE, null, 3));

        assertThat(dto.points()).isEmpty();
        assertThat(dto.gaps()).extracting(MatrixSeriesDto.Gap::size).containsExactly(50, 100, 200, 316);
        assertThat(dto.gaps()).extracting(MatrixSeriesDto.Gap::newestRunId).containsExactly(4L, 3L, 2L, 1L);
        assertThat(dto.gaps()).extracting(MatrixSeriesDto.Gap::newestOutcome).containsExactly(CollectiveOutcome.PARTIAL,
                CollectiveOutcome.FAILED, CollectiveOutcome.REFUSED, CollectiveOutcome.COMPLETE);
        assertThat(dto.sizesWithSpeedup()).isZero();
        assertThat(dto.done()).isFalse();
        assertThat(dto.basis()).isNull();
    }

    @Test
    @DisplayName("an older COMPLETE run is used when the newest of that size is PARTIAL")
    void olderCompleteBehindNewerPartial() {
        MatrixSeriesDto dto = series(run(2, 100, CollectiveOutcome.PARTIAL, null, 3), run(1, 100, CollectiveOutcome.COMPLETE, 1.2, 3));

        assertThat(dto.points()).singleElement().satisfies(p -> {
            assertThat(p.size()).isEqualTo(100);
            assertThat(p.sourceRunId()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("a size never run is a gap with null run and outcome; sizes outside the configured series are ignored")
    void neverRunAndOtherSizes() {
        MatrixSeriesDto dto = series(run(1, 77, CollectiveOutcome.COMPLETE, 1.0, 3));

        assertThat(dto.points()).isEmpty();
        assertThat(dto.gaps()).hasSize(4).allSatisfy(gap -> {
            assertThat(gap.newestRunId()).isNull();
            assertThat(gap.newestOutcome()).isNull();
        });
        assertThat(dto.seriesSizes()).isEqualTo(SIZES);
        assertThat(MatrixSeries.from(Collections.emptyList(), SIZES, MatrixTestResults.BASIS, 1).gaps()).hasSize(4);
    }

    @Test
    @DisplayName("done needs three sizes with a speedup; sizesRequired is 3")
    void doneAtThree() {
        MatrixSeriesDto two = series(run(2, 100, CollectiveOutcome.COMPLETE, 1.0, 3), run(1, 50, CollectiveOutcome.COMPLETE, 0.5, 3));
        assertThat(two.sizesWithSpeedup()).isEqualTo(2);
        assertThat(two.sizesRequired()).isEqualTo(3);
        assertThat(two.done()).isFalse();

        MatrixSeriesDto three = series(run(3, 200, CollectiveOutcome.COMPLETE, 1.5, 3), run(2, 100, CollectiveOutcome.COMPLETE, 1.0, 3),
                run(1, 50, CollectiveOutcome.COMPLETE, 0.5, 3));
        assertThat(three.sizesWithSpeedup()).isEqualTo(3);
        assertThat(three.done()).isTrue();
    }

    @Test
    @DisplayName("mixed rank counts are flagged; the basis uses the largest rank count; one rank count needs no note")
    void ranks() {
        MatrixSeriesDto mixed = series(run(2, 100, CollectiveOutcome.COMPLETE, 1.0, 2), run(1, 50, CollectiveOutcome.COMPLETE, 0.5, 3));
        assertThat(mixed.ranksDiffer()).isTrue();
        assertThat(mixed.ranksNote()).isEqualTo(MatrixTexts.RANKS_NOTE);
        assertThat(mixed.basis()).isEqualTo(MatrixTexts.speedupBasis(MatrixTestResults.BASIS, 3, 1));

        MatrixSeriesDto same = series(run(1, 50, CollectiveOutcome.COMPLETE, 0.5, 3));
        assertThat(same.ranksDiffer()).isFalse();
        assertThat(same.ranksNote()).isNull();
        assertThat(same.rule()).isEqualTo(MatrixTexts.SERIES_RULE);
    }
}
