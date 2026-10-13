package com.udcf.modules.matrix;

import com.udcf.modules.matrix.MatrixTiming.RankCompute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatrixTimingTest {

    private static final List<RankCompute> RANKS = List.of(new RankCompute(0, 1, 34, 300L), new RankCompute(1, 2, 33, 500L),
            new RankCompute(2, 3, 33, 400L));

    private static MatrixTiming timing(Long sequential, Long scatter, Long broadcast, Long compute, Long gather, Long total,
                                       List<RankCompute> ranks) {
        return new MatrixTiming(100, 0, sequential, scatter, broadcast, compute, gather, total, ranks);
    }

    @Test
    @DisplayName("derived figures from measured ones: speedup, communication, other, share, compute bound")
    void derived() {
        MatrixTiming t = timing(1_500L, 100L, 200L, 600L, 300L, 1_250L, RANKS);
        assertThat(t.speedup()).isEqualTo(1.2);
        assertThat(t.communicationNanos()).isEqualTo(600L);
        assertThat(t.otherNanos()).isEqualTo(50L);
        assertThat(t.communicationShare()).isEqualTo(0.48);
        assertThat(t.computeBoundSpeedup()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("speedup is null when the sequential or the total time is unknown, or the total is 0")
    void speedupNulls() {
        assertThat(timing(null, 1L, 1L, 1L, 1L, 10L, RANKS).speedup()).isNull();
        assertThat(timing(10L, 1L, 1L, 1L, 1L, null, RANKS).speedup()).isNull();
        assertThat(timing(10L, 1L, 1L, 1L, 1L, 0L, RANKS).speedup()).isNull();
        assertThat(timing(10L, 1L, 1L, 1L, 1L, 0L, RANKS).communicationShare()).isNull();
    }

    @Test
    @DisplayName("communication and other are null when any phase is unknown")
    void phaseNulls() {
        assertThat(timing(10L, null, 1L, 1L, 1L, 10L, RANKS).communicationNanos()).isNull();
        assertThat(timing(10L, 1L, null, 1L, 1L, 10L, RANKS).otherNanos()).isNull();
        assertThat(timing(10L, 1L, 1L, 1L, null, 10L, RANKS).communicationShare()).isNull();
        assertThat(timing(10L, 1L, 1L, null, 1L, 10L, RANKS).otherNanos()).isNull();
        assertThat(timing(10L, 1L, 1L, 1L, 1L, null, RANKS).otherNanos()).isNull();
    }

    @Test
    @DisplayName("other can be negative and is reported as it is, never clamped")
    void otherNotClamped() {
        assertThat(timing(10L, 40L, 40L, 40L, 40L, 100L, RANKS).otherNanos()).isEqualTo(-60L);
    }

    @Test
    @DisplayName("the compute bound is null with an unknown rank, no rank, an unknown sequential time or a slowest compute of 0")
    void computeBoundNulls() {
        List<RankCompute> oneUnknown = List.of(new RankCompute(0, 1, 5, 300L), new RankCompute(1, 2, 5, null));
        assertThat(timing(1_000L, 1L, 1L, 1L, 1L, 10L, oneUnknown).computeBoundSpeedup()).isNull();
        assertThat(timing(1_000L, 1L, 1L, 1L, 1L, 10L, List.of()).computeBoundSpeedup()).isNull();
        assertThat(timing(null, 1L, 1L, 1L, 1L, 10L, RANKS).computeBoundSpeedup()).isNull();
        assertThat(timing(1_000L, 1L, 1L, 1L, 1L, 10L, List.of(new RankCompute(0, 1, 5, 0L))).computeBoundSpeedup()).isNull();
    }

    @Test
    @DisplayName("the work is n cubed, exactly")
    void work() {
        assertThat(MatrixTiming.multiplyAdds(316)).isEqualTo(31_554_496L);
        assertThat(MatrixTiming.multiplyAdds(46_340)).isEqualTo(99_510_312_104_000L);
        assertThat(timing(1L, 1L, 1L, 1L, 1L, 1L, RANKS).multiplyAdds()).isEqualTo(1_000_000L);
        assertThatThrownBy(() -> MatrixTiming.multiplyAdds(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("negative times, negative warm-up counts, a bad n and ranks out of order are refused")
    void validation() {
        assertThatThrownBy(() -> timing(-1L, 1L, 1L, 1L, 1L, 1L, RANKS)).hasMessage("sequentialNanos must be >= 0, was -1");
        assertThatThrownBy(() -> timing(1L, 1L, 1L, 1L, 1L, -2L, RANKS)).hasMessage("parallelTotalNanos must be >= 0, was -2");
        assertThatThrownBy(() -> new MatrixTiming(10, -1, 1L, 1L, 1L, 1L, 1L, 1L, RANKS))
                .hasMessage("warmupProducts must be >= 0, was -1");
        assertThatThrownBy(() -> new MatrixTiming(0, 0, 1L, 1L, 1L, 1L, 1L, 1L, RANKS)).hasMessage("n must be >= 1, was 0");
        assertThatThrownBy(() -> timing(1L, 1L, 1L, 1L, 1L, 1L, List.of(RANKS.get(1)))).hasMessageContaining("rank order");
        assertThat(new MatrixTiming(10, 3, null, null, null, null, null, null, List.of()).warmupProducts()).isEqualTo(3);
    }
}
