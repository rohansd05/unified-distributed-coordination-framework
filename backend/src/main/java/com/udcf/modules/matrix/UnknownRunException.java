package com.udcf.modules.matrix;

/**
 * Thrown when no kept run has the requested id, or (with a {@code null} id) when none has run yet.
 * Mapped to HTTP 404 by {@link MatrixController}.
 *
 * <p>No dedicated test: a constructor and an accessor only.</p>
 */
public class UnknownRunException extends RuntimeException {

    private final Long runId;

    public UnknownRunException(Long runId) {
        super(runId == null
                ? "No matrix run has run yet"
                : "No matrix run with id " + runId + " (only the latest runs are kept, and ids restart at 1 after a reset)");
        this.runId = runId;
    }

    public Long runId() {
        return runId;
    }
}
