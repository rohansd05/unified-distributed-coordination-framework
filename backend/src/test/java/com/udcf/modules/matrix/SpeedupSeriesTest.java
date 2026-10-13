package com.udcf.modules.matrix;

import com.udcf.modules.matrix.SpeedupSeries.Point;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeedupSeriesTest {

    private static MatrixTiming timing(int n, Long sequential, Long total) {
        return new MatrixTiming(n, 0, sequential, 10L, 20L, 30L, 40L, total, List.of());
    }

    @Test
    @DisplayName("a point copies the measured figures and the derived communication and speedup")
    void pointOf() {
        assertThat(Point.of(timing(50, 400L, 200L))).isEqualTo(new Point(50, 400L, 200L, 70L, 30L, 2.0));
    }

    @Test
    @DisplayName("points are sorted by size; a size without a speedup is kept and not counted")
    void sortedAndCounted() {
        SpeedupSeries series = SpeedupSeries.of(List.of(timing(200, 10L, 5L), timing(50, 10L, 20L), timing(100, null, 5L)));
        assertThat(series.points()).extracting(Point::n).containsExactly(50, 100, 200);
        assertThat(series.points().get(1).speedup()).isNull();
        assertThat(series.sizesWithSpeedup()).isEqualTo(2);
        assertThat(new SpeedupSeries(List.of()).sizesWithSpeedup()).isZero();
    }

    @Test
    @DisplayName("a size given twice, or a size below 1, is refused")
    void refused() {
        assertThatThrownBy(() -> SpeedupSeries.of(List.of(timing(50, 1L, 1L), timing(50, 2L, 2L))))
                .hasMessage("size 50 appears twice");
        assertThatThrownBy(() -> new Point(0, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
