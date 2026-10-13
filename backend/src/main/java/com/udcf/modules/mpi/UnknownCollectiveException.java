package com.udcf.modules.mpi;

/**
 * Thrown when no kept collective has the requested id, or (with a {@code null} id) when none has
 * run yet. Mapped to HTTP 404 by {@link MpiController}.
 *
 * <p>No dedicated test: a constructor and an accessor only.</p>
 */
public class UnknownCollectiveException extends RuntimeException {

    private final Long collectiveId;

    public UnknownCollectiveException(Long collectiveId) {
        super(collectiveId == null
                ? "No collective has run yet"
                : "No collective with id " + collectiveId + " (only the latest collectives are kept, and ids restart at 1 after a reset)");
        this.collectiveId = collectiveId;
    }

    public Long collectiveId() {
        return collectiveId;
    }
}
