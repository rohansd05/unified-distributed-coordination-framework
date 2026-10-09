package com.udcf.modules.mapreduce;

/**
 * Thrown when no kept run has the requested id, or (with a {@code null} id) when no run has
 * been started yet. Mapped to HTTP 404 by {@link MapReduceController}.
 *
 * <p>No dedicated test: a constructor and an accessor only.</p>
 */
public class UnknownRunException extends RuntimeException {

    private final String runId;

    public UnknownRunException(String runId) {
        super(runId == null
                ? "No MapReduce run has been started yet"
                : "No MapReduce run with id '" + runId + "' (only the latest runs are kept)");
        this.runId = runId;
    }

    public String runId() {
        return runId;
    }
}
