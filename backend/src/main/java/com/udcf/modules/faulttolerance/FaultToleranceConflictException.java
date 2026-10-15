package com.udcf.modules.faulttolerance;

import java.util.Objects;

/**
 * An Experiment 8 action that is not possible in the current state (HTTP 409, with a
 * {@code reason} code the page can switch on and a sentence it can show).
 *
 * <p>No dedicated test: a constructor and accessors; FaultToleranceControllerTest checks the 409 bodies.</p>
 */
public class FaultToleranceConflictException extends RuntimeException {

    /** Why the action was refused. */
    public enum Reason {
        NO_SERVING_PRIMARY,
        NOTHING_TO_RECOVER,
        STREAM_RUNNING,
        STREAM_NOT_RUNNING,
        UPDATE_LIMIT_REACHED
    }

    private final Reason reason;

    public FaultToleranceConflictException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public Reason reason() {
        return reason;
    }
}
