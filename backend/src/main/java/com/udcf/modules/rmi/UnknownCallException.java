package com.udcf.modules.rmi;

/**
 * Thrown when no kept call or comparison has the requested id, or (with a {@code null} id) when
 * none has been made yet. Mapped to HTTP 404 by {@link RmiController}.
 *
 * <p>No dedicated test: a constructor and an accessor only.</p>
 */
public class UnknownCallException extends RuntimeException {

    private final Long callId;

    public UnknownCallException(Long callId) {
        super(callId == null
                ? "No RMI call has been made yet"
                : "No RMI call with id " + callId + " (only the latest calls are kept, and ids restart at 1 after a reset)");
        this.callId = callId;
    }

    public Long callId() {
        return callId;
    }
}
