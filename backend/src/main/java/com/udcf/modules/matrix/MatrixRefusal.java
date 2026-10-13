package com.udcf.modules.matrix;

import java.util.Objects;

/**
 * Why a matrix run was refused before anything was generated or sent, with a plain sentence for
 * the page. Covered by MatrixLimitsTest and MatrixRecordsTest.
 */
public record MatrixRefusal(Reason reason, String message) {

    /**
     * The refusal reasons, in the order {@link MatrixLimits#check} tests them.
     *
     * <p>No dedicated test: an enum without behaviour (R6).</p>
     */
    public enum Reason {
        /** N is above floor(sqrt(max-payload-elements)): B would not fit one broadcast. */
        SIZE_ABOVE_MAXIMUM,
        /** N is below {@code udcf.matrix.min-size}. */
        SIZE_BELOW_MINIMUM,
        /** No node is live, so there is no participant at all. */
        NO_LIVE_PARTICIPANT,
        /** N is below the number of live participants: a rank would get no row. */
        SIZE_BELOW_PARTICIPANTS,
        /** The root is not a participant or is not live (checked by the E10b runner after the four above). */
        ROOT_UNAVAILABLE
    }

    public MatrixRefusal {
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
