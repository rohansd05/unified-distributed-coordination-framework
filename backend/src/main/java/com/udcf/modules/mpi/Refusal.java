package com.udcf.modules.mpi;

import java.util.Objects;

/**
 * Why a collective was refused, with a plain sentence for the page.
 *
 * <p>No dedicated test: a record with null checks only (R6); covered by RankAssignmentTest and
 * CollectiveExecutorTest.</p>
 */
public record Refusal(RefusalReason reason, String message) {

    public Refusal {
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
