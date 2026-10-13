package com.udcf.modules.mpi;

/**
 * Why a collective was refused before anything was sent.
 *
 * <p>No dedicated test: an enum without behaviour (R6).</p>
 */
public enum RefusalReason {
    /** The request names no participant. */
    NO_PARTICIPANTS,
    /** The root is not one of the participants. */
    ROOT_NOT_PARTICIPANT,
    /** The root is not live at the start. */
    ROOT_DOWN,
    /** Scatter or gather with fewer elements than live participants: a rank would get nothing. */
    COUNT_BELOW_PARTICIPANTS,
    /** More elements than {@code max-payload-elements}. */
    PAYLOAD_TOO_LARGE
}
