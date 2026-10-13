package com.udcf.modules.matrix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The exactness rule: the product assembled from row blocks is bitwise equal to the sequential product. */
class ParallelProductTest {

    private static long[] bits(double[] values) {
        long[] bits = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            bits[i] = Double.doubleToLongBits(values[i]);
        }
        return bits;
    }

    /** An independent textbook i-j-k product with the same accumulation order (k ascending from +0.0). */
    private static double[] reference(double[] a, double[] b, int n) {
        double[] c = new double[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double s = 0.0;
                for (int k = 0; k < n; k++) {
                    s = s + a[i * n + k] * b[k * n + j];
                }
                c[i * n + j] = s;
            }
        }
        return c;
    }

    private static double[] assembled(double[] a, double[] b, int n, int p) {
        double[] c = new double[n * n];
        for (RowPartition.RowBlock block : RowPartition.of(n, p)) {
            double[] rows = MatrixMultiply.multiplyRows(RowPartition.rows(a, block), b, n);
            System.arraycopy(rows, 0, c, block.elementOffset(), rows.length);
        }
        return c;
    }

    @ParameterizedTest(name = "n = {0}")
    @ValueSource(ints = {1, 2, 3, 7, 16, 50, 101})
    @DisplayName("for every p from 1 to 7 (p <= n), the assembled blocks equal the sequential product and the reference bitwise")
    void assembledEqualsSequential(int n) {
        for (long seed : new long[]{1L, 42L, -7L}) {
            double[] a = MatrixGenerator.a(seed, n);
            double[] b = MatrixGenerator.b(seed, n);
            long[] sequential = bits(MatrixMultiply.multiply(a, b, n));
            assertThat(sequential).isEqualTo(bits(reference(a, b, n)));
            for (int p = 1; p <= Math.min(7, n); p++) {
                assertThat(bits(assembled(a, b, n, p))).as("n %d, p %d, seed %d", n, p, seed).isEqualTo(sequential);
            }
        }
    }

    @Test
    @DisplayName("the bitwise check is sensitive: summing k in descending order changes some elements")
    void orderMatters() {
        int n = 16;
        double[] a = MatrixGenerator.a(42, n);
        double[] b = MatrixGenerator.b(42, n);
        double[] sequential = MatrixMultiply.multiply(a, b, n);
        int differing = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double s = 0.0;
                for (int k = n - 1; k >= 0; k--) {
                    s = s + a[i * n + k] * b[k * n + j];
                }
                if (Double.doubleToLongBits(s) != Double.doubleToLongBits(sequential[i * n + j])) {
                    differing++;
                }
            }
        }
        assertThat(differing).isPositive();
    }
}
