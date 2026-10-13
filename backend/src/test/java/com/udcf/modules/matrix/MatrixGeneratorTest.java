package com.udcf.modules.matrix;

import com.udcf.modules.mpi.PayloadGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatrixGeneratorTest {

    @Test
    @DisplayName("the same seed and size always give the same A and B")
    void deterministic() {
        assertThat(MatrixGenerator.a(42, 7)).isEqualTo(MatrixGenerator.a(42, 7));
        assertThat(MatrixGenerator.b(42, 7)).isEqualTo(MatrixGenerator.b(42, 7));
        assertThat(MatrixGenerator.a(42, 7)).isNotEqualTo(MatrixGenerator.a(43, 7));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 42L, -1L, Long.MIN_VALUE, Long.MAX_VALUE})
    @DisplayName("A and B come from different derived seeds and differ")
    void aAndBDiffer(long seed) {
        assertThat(MatrixGenerator.seedA(seed)).isNotEqualTo(MatrixGenerator.seedB(seed));
        assertThat(MatrixGenerator.a(seed, 5)).isNotEqualTo(MatrixGenerator.b(seed, 5));
    }

    @Test
    @DisplayName("A and B are exactly Experiment 9's vectors of the derived seeds, so a collective can regenerate them")
    void reusesPayloadGenerator() {
        assertThat(MatrixGenerator.seedA(42)).isEqualTo(42L ^ 0xA5A5A5A5A5A5A5A5L);
        assertThat(MatrixGenerator.seedB(42)).isEqualTo(42L ^ 0x5A5A5A5A5A5A5A5AL);
        assertThat(MatrixGenerator.a(42, 6)).isEqualTo(PayloadGenerator.vector(MatrixGenerator.seedA(42), 36));
        assertThat(MatrixGenerator.b(42, 6)).isEqualTo(PayloadGenerator.vector(MatrixGenerator.seedB(42), 36));
    }

    @Test
    @DisplayName("n x n values, every one finite and in [-1000, 1000)")
    void sizeAndRange() {
        double[] a = MatrixGenerator.a(7, 50);
        double[] b = MatrixGenerator.b(7, 50);
        assertThat(a).hasSize(2500);
        assertThat(b).hasSize(2500);
        for (double[] matrix : new double[][]{a, b}) {
            for (double value : matrix) {
                assertThat(Double.isFinite(value)).isTrue();
                assertThat(value).isGreaterThanOrEqualTo(-1000.0).isLessThan(1000.0);
            }
        }
    }

    @Test
    @DisplayName("a size below 1, or one whose square does not fit an int, is refused")
    void badSizes() {
        assertThatThrownBy(() -> MatrixGenerator.a(1, 0)).isInstanceOf(IllegalArgumentException.class).hasMessage("n must be >= 1, was 0");
        assertThatThrownBy(() -> MatrixGenerator.b(1, 46341)).isInstanceOf(ArithmeticException.class);
    }
}
