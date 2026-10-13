package com.udcf.modules.matrix;

import com.udcf.modules.mpi.MpiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatrixLimitsTest {

    private static MpiProperties mpi(int maxPayloadElements) {
        return new MpiProperties(maxPayloadElements, 10_000, 20, 8, 300);
    }

    private static MatrixLimits limits(int minSize, int maxPayloadElements, Integer... series) {
        return new MatrixLimits(new MatrixProperties(minSize, List.of(series)), mpi(maxPayloadElements));
    }

    @ParameterizedTest(name = "floorSqrt({0}) = {1}")
    @CsvSource({"1,1", "3,1", "4,2", "99,9", "100,10", "101,10", "9999,99", "10000,100", "10001,100",
            "99855,315", "99856,316", "99999,316", "100000,316", "100488,316", "100489,317", "2147483647,46340"})
    @DisplayName("floorSqrt is exact at perfect squares and one below and above them")
    void floorSqrt(int x, int expected) {
        assertThat(MatrixLimits.floorSqrt(x)).isEqualTo(expected);
    }

    @Test
    @DisplayName("floorSqrt refuses values below 1")
    void floorSqrtBelowOne() {
        assertThatThrownBy(() -> MatrixLimits.floorSqrt(0)).hasMessage("x must be >= 1, was 0");
    }

    @Test
    @DisplayName("the maximum size is derived from max-payload-elements: 316 locally, 100 in public")
    void maximumFromMpi() {
        assertThat(limits(2, 100_000, 50, 100, 316).maxSize()).isEqualTo(316);
        assertThat(limits(2, 10_000, 25, 50, 100).maxSize()).isEqualTo(100);
        assertThat(limits(2, 10_000, 25, 50, 100).minSize()).isEqualTo(2);
        assertThat(limits(2, 10_000, 25, 50, 100).seriesSizes()).containsExactly(25, 50, 100);
    }

    @Test
    @DisplayName("a series size above the maximum, or a minimum above it, fails construction")
    void seriesAboveMaximum() {
        assertThatThrownBy(() -> limits(2, 10_000, 25, 50, 101)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("101").hasMessageContaining("maximum size 100");
        assertThatThrownBy(() -> new MatrixLimits(new MatrixProperties(11, List.of(11, 12, 13)), mpi(100)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("min-size 11 is above the maximum size 10");
    }

    @Test
    @DisplayName("each refusal reason, in order, with its figures")
    void refusals() {
        MatrixLimits limits = limits(2, 10_000, 25, 50, 100);
        assertThat(limits.check(101, 3)).get().extracting(MatrixRefusal::reason).isEqualTo(MatrixRefusal.Reason.SIZE_ABOVE_MAXIMUM);
        assertThat(limits.check(101, 0)).get().extracting(MatrixRefusal::reason).isEqualTo(MatrixRefusal.Reason.SIZE_ABOVE_MAXIMUM);
        assertThat(limits.check(1, 3)).get().extracting(MatrixRefusal::reason).isEqualTo(MatrixRefusal.Reason.SIZE_BELOW_MINIMUM);
        assertThat(limits.check(50, 0)).get().extracting(MatrixRefusal::reason).isEqualTo(MatrixRefusal.Reason.NO_LIVE_PARTICIPANT);
        MatrixRefusal fewRows = limits.check(2, 3).orElseThrow();
        assertThat(fewRows.reason()).isEqualTo(MatrixRefusal.Reason.SIZE_BELOW_PARTICIPANTS);
        assertThat(fewRows.message()).isEqualTo("Size 2 gives fewer rows than the 3 live participants, so a rank would get no row.");
        assertThat(limits.check(101, 3).orElseThrow().message())
                .isEqualTo("Size 101 is above the maximum 100: B (101 x 101) would not fit one broadcast.");
    }

    @Test
    @DisplayName("the boundaries are accepted: n = maximum, n = minimum, n = live participants")
    void boundariesAccepted() {
        MatrixLimits limits = limits(2, 10_000, 25, 50, 100);
        assertThat(limits.check(100, 3)).isEmpty();
        assertThat(limits.check(2, 2)).isEmpty();
        assertThat(limits.check(3, 3)).isEmpty();
        assertThatThrownBy(() -> limits.check(3, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
