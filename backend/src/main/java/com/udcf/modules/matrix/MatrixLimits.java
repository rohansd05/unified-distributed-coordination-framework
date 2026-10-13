package com.udcf.modules.matrix;

import com.udcf.modules.mpi.MpiProperties;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The sizes Experiment 10 accepts. The largest N is derived, never configured: the whole of B
 * (N x N elements) is the largest single vector sent (one broadcast), and a collective moves at most
 * {@code udcf.mpi.max-payload-elements} values, so N &lt;= floor(sqrt(max-payload-elements)):
 * 316 locally (100000) and 100 in the public profile (10000).
 *
 * <p>The constructor checks the configured series against that bound, so an inconsistent YAML
 * fails at startup once a bean is built from it (E10c).</p>
 */
public final class MatrixLimits {

    private final int minSize;
    private final int maxSize;
    private final List<Integer> seriesSizes;

    public MatrixLimits(MatrixProperties matrix, MpiProperties mpi) {
        Objects.requireNonNull(matrix, "matrix must not be null");
        Objects.requireNonNull(mpi, "mpi must not be null");
        this.minSize = matrix.minSize();
        this.maxSize = floorSqrt(mpi.maxPayloadElements());
        this.seriesSizes = matrix.seriesSizes();
        if (minSize > maxSize) {
            throw new IllegalArgumentException("udcf.matrix.min-size " + minSize + " is above the maximum size " + maxSize
                    + " = floor(sqrt(udcf.mpi.max-payload-elements " + mpi.maxPayloadElements() + "))");
        }
        for (int size : seriesSizes) {
            if (size > maxSize) {
                throw new IllegalArgumentException("udcf.matrix.series-sizes " + seriesSizes + " has " + size
                        + ", above the maximum size " + maxSize + " = floor(sqrt(udcf.mpi.max-payload-elements "
                        + mpi.maxPayloadElements() + "))");
            }
        }
    }

    /**
     * The largest r with r * r &lt;= x, in exact integer arithmetic: the double square root only gives
     * the starting point, and long squares correct it, so no rounding error can leak in.
     *
     * @throws IllegalArgumentException if x &lt; 1
     */
    public static int floorSqrt(int x) {
        if (x < 1) {
            throw new IllegalArgumentException("x must be >= 1, was " + x);
        }
        long r = (long) Math.sqrt(x);
        while (r * r > x) {
            r--;
        }
        while ((r + 1) * (r + 1) <= x) {
            r++;
        }
        return (int) r;
    }

    public int minSize() {
        return minSize;
    }

    /** floor(sqrt(max-payload-elements)). */
    public int maxSize() {
        return maxSize;
    }

    public List<Integer> seriesSizes() {
        return seriesSizes;
    }

    /**
     * Why a run of size {@code n} over {@code liveParticipants} live nodes must be refused; empty when
     * it may run. Checked in this order: above the maximum, below the minimum, no live participant,
     * fewer rows than live participants.
     */
    public Optional<MatrixRefusal> check(int n, int liveParticipants) {
        if (liveParticipants < 0) {
            throw new IllegalArgumentException("liveParticipants must be >= 0, was " + liveParticipants);
        }
        if (n > maxSize) {
            return refusal(MatrixRefusal.Reason.SIZE_ABOVE_MAXIMUM, "Size " + n + " is above the maximum " + maxSize
                    + ": B (" + n + " x " + n + ") would not fit one broadcast.");
        }
        if (n < minSize) {
            return refusal(MatrixRefusal.Reason.SIZE_BELOW_MINIMUM, "Size " + n + " is below the minimum " + minSize + ".");
        }
        if (liveParticipants == 0) {
            return refusal(MatrixRefusal.Reason.NO_LIVE_PARTICIPANT, "No node is live, so no node can take part.");
        }
        if (n < liveParticipants) {
            return refusal(MatrixRefusal.Reason.SIZE_BELOW_PARTICIPANTS, "Size " + n + " gives fewer rows than the "
                    + liveParticipants + " live participants, so a rank would get no row.");
        }
        return Optional.empty();
    }

    private static Optional<MatrixRefusal> refusal(MatrixRefusal.Reason reason, String message) {
        return Optional.of(new MatrixRefusal(reason, message));
    }
}
