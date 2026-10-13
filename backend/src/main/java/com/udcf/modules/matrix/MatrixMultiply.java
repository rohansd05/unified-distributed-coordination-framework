package com.udcf.modules.matrix;

import com.udcf.modules.rmi.CallTimer;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The one multiply of Experiment 10: a block of rows of A times the whole of B. The sequential
 * product is the same method over all n rows, so the sequential and the parallel results run the
 * same code.
 *
 * <p><b>Exactness.</b> Every element is {@code s = +0.0; for k = 0 .. n-1: s = s + a[i][k] * b[k][j]},
 * a plain multiply then a plain add, with k ascending. The loops run in i-k-j order (B is read row
 * by row, which is faster), which does not change the order of the additions into any one element.
 * No {@code Math.fma}, no reordering, no parallel reduction, and each element is computed entirely
 * inside one block. Since Java 17 (JEP 306, JLS 15.4) every floating-point operation is strict IEEE
 * 754 binary64 with round-to-nearest: the product is rounded, then the sum, and neither javac nor the
 * JIT may fuse or widen them. The same operand bits in the same order therefore give the same result
 * bits on every JVM, thread and node: an assembled parallel result is bitwise equal to the sequential
 * one (proved by ParallelProductTest).</p>
 */
public final class MatrixMultiply {

    /**
     * A product and how long it took.
     *
     * @param values the rows of the product, row-major
     * @param nanos  measured around the multiply only (no copying, checking or checksum)
     */
    public record Timed(double[] values, long nanos) {

        public Timed {
            Objects.requireNonNull(values, "values must not be null");
            if (nanos < 0) {
                throw new IllegalArgumentException("nanos must be >= 0, was " + nanos);
            }
        }
    }

    private MatrixMultiply() {
    }

    /** A x B for n x n row-major matrices. */
    public static double[] multiply(double[] a, double[] b, int n) {
        return multiplyRows(a, b, n);
    }

    /**
     * {@code aRows} (r whole rows of A, row-major, r * n values) times B (n x n): the same r rows of
     * A x B.
     */
    public static double[] multiplyRows(double[] aRows, double[] b, int n) {
        int rows = checkedRows(aRows, b, n);
        double[] c = new double[rows * n];
        for (int i = 0; i < rows; i++) {
            int rowOffset = i * n;
            for (int k = 0; k < n; k++) {
                double aik = aRows[rowOffset + k];
                int bRow = k * n;
                for (int j = 0; j < n; j++) {
                    // c starts at +0.0, and each element gains its terms in ascending k: the exactness rule.
                    c[rowOffset + j] = c[rowOffset + j] + aik * b[bRow + j];
                }
            }
        }
        return c;
    }

    /**
     * {@link #multiplyRows}, timed with {@code nanoClock} around the multiply only. The arguments are
     * checked before the clock is read.
     *
     * @throws IllegalStateException if the time source goes backwards
     */
    public static Timed timedRows(LongSupplier nanoClock, double[] aRows, double[] b, int n) {
        Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        checkedRows(aRows, b, n);
        CallTimer.Measurement<double[]> made = new CallTimer(nanoClock).measure(() -> multiplyRows(aRows, b, n));
        if (made.failed()) {
            throw new IllegalStateException("the multiply failed after its arguments were checked", made.failure());
        }
        return new Timed(made.value(), made.elapsedNanos());
    }

    /** The number of rows in {@code aRows}, after checking every length. */
    private static int checkedRows(double[] aRows, double[] b, int n) {
        Objects.requireNonNull(aRows, "aRows must not be null");
        Objects.requireNonNull(b, "b must not be null");
        int elements = MatrixGenerator.elements(n);
        if (b.length != elements) {
            throw new IllegalArgumentException("b has " + b.length + " values, not " + n + " x " + n);
        }
        if (aRows.length == 0 || aRows.length % n != 0 || aRows.length > elements) {
            throw new IllegalArgumentException("aRows has " + aRows.length + " values, not 1 to " + n + " whole rows of " + n);
        }
        return aRows.length / n;
    }
}
