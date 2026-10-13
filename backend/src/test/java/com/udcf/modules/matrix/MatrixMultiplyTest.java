package com.udcf.modules.matrix;

import com.udcf.modules.rmi.ScriptedNanoClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatrixMultiplyTest {

    private static double[] identity(int n) {
        double[] id = new double[n * n];
        for (int i = 0; i < n; i++) {
            id[i * n + i] = 1.0;
        }
        return id;
    }

    private static long[] bits(double[] values) {
        long[] bits = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            bits[i] = Double.doubleToLongBits(values[i]);
        }
        return bits;
    }

    @Test
    @DisplayName("2 x 2 by hand: [1 2; 3 4] x [5 6; 7 8] = [19 22; 43 50]")
    void twoByTwo() {
        assertThat(MatrixMultiply.multiply(new double[]{1, 2, 3, 4}, new double[]{5, 6, 7, 8}, 2)).containsExactly(19, 22, 43, 50);
    }

    @Test
    @DisplayName("3 x 3 by hand: [1..9] x [9..1] = [30 24 18; 84 69 54; 138 114 90]")
    void threeByThree() {
        double[] a = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        double[] b = {9, 8, 7, 6, 5, 4, 3, 2, 1};
        assertThat(MatrixMultiply.multiply(a, b, 3)).containsExactly(30, 24, 18, 84, 69, 54, 138, 114, 90);
    }

    @Test
    @DisplayName("A x I = A and I x A = A, bitwise")
    void identityKeepsBits() {
        double[] a = MatrixGenerator.a(11, 9);
        assertThat(bits(MatrixMultiply.multiply(a, identity(9), 9))).isEqualTo(bits(a));
        assertThat(bits(MatrixMultiply.multiply(identity(9), a, 9))).isEqualTo(bits(a));
    }

    @Test
    @DisplayName("a zero matrix gives zeros (+0.0)")
    void zero() {
        double[] product = MatrixMultiply.multiply(new double[16], MatrixGenerator.b(3, 4), 4);
        assertThat(product).hasSize(16);
        for (double value : product) {
            assertThat(value).isZero();
        }
    }

    @Test
    @DisplayName("a block of rows gives exactly those rows of the full product")
    void blockOfRows() {
        double[] a = MatrixGenerator.a(5, 6);
        double[] b = MatrixGenerator.b(5, 6);
        double[] full = MatrixMultiply.multiply(a, b, 6);
        RowPartition.RowBlock block = new RowPartition.RowBlock(1, 2, 3, 6);
        assertThat(bits(MatrixMultiply.multiplyRows(RowPartition.rows(a, block), b, 6)))
                .isEqualTo(bits(RowPartition.rows(full, block)));
    }

    @Test
    @DisplayName("wrong lengths are refused before any work")
    void wrongLengths() {
        assertThatThrownBy(() -> MatrixMultiply.multiply(new double[4], new double[3], 2)).hasMessageContaining("b has 3 values");
        assertThatThrownBy(() -> MatrixMultiply.multiplyRows(new double[3], new double[4], 2)).hasMessageContaining("aRows has 3 values");
        assertThatThrownBy(() -> MatrixMultiply.multiplyRows(new double[0], new double[4], 2)).hasMessageContaining("aRows has 0 values");
        assertThatThrownBy(() -> MatrixMultiply.multiplyRows(new double[6], new double[4], 2)).hasMessageContaining("aRows has 6 values");
    }

    @Test
    @DisplayName("timedRows: the time is the difference of exactly two clock reads around the multiply")
    void timed() {
        ScriptedNanoClock clock = new ScriptedNanoClock(1_000, 4_500);
        MatrixMultiply.Timed timed = MatrixMultiply.timedRows(clock, new double[]{1, 2, 3, 4}, new double[]{5, 6, 7, 8}, 2);
        assertThat(timed.nanos()).isEqualTo(3_500);
        assertThat(timed.values()).containsExactly(19, 22, 43, 50);
        assertThat(clock.reads()).isEqualTo(2);
    }

    @Test
    @DisplayName("timedRows: bad arguments are refused before the clock is read, and a clock going backwards throws")
    void timedFailures() {
        ScriptedNanoClock unread = new ScriptedNanoClock(0);
        assertThatThrownBy(() -> MatrixMultiply.timedRows(unread, new double[3], new double[4], 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(unread.reads()).isZero();
        assertThatThrownBy(() -> MatrixMultiply.timedRows(new ScriptedNanoClock(10, 9), new double[4], new double[4], 2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("backwards");
        assertThatThrownBy(() -> new MatrixMultiply.Timed(new double[1], -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
