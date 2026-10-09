package com.udcf.modules.faulttolerance;

import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.modules.replication.WriteResult;

import java.util.Objects;

/**
 * How one client update ended ({@link FailoverCluster#submit}).
 *
 * <p>Covered by FailoverRecordsTest (validation) and FailoverClusterTest.</p>
 *
 * @param update   the update
 * @param model    the consistency model it was written with
 * @param status   ACCEPTED (the client was told it succeeded) or GAVE_UP (attempts used up)
 * @param nodeId   the primary that accepted it; null if it gave up
 * @param epoch    the epoch it was accepted at; null if it gave up
 * @param attempts sends and discoveries used
 * @param result   the replication service's result; null if it gave up
 */
public record UpdateOutcome(SystemUpdate update, ConsistencyModel model, Status status, Integer nodeId, Long epoch,
                            int attempts, WriteResult result) {

    /** The two ends of an update. */
    public enum Status {
        ACCEPTED,
        GAVE_UP
    }

    public UpdateOutcome {
        Objects.requireNonNull(update, "update must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(status, "status must not be null");
        boolean accepted = status == Status.ACCEPTED;
        if (accepted != (result != null) || accepted != (nodeId != null) || accepted != (epoch != null)) {
            throw new IllegalArgumentException("nodeId, epoch and result are given exactly when the update was accepted");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must be >= 0, was " + attempts);
        }
    }

    static UpdateOutcome accepted(SystemUpdate update, int nodeId, int attempts, WriteResult result) {
        return new UpdateOutcome(update, result.model(), Status.ACCEPTED, nodeId, result.item().epoch(), attempts,
                result);
    }

    static UpdateOutcome gaveUp(SystemUpdate update, ConsistencyModel model, int attempts) {
        return new UpdateOutcome(update, model, Status.GAVE_UP, null, null, attempts, null);
    }

    public boolean accepted() {
        return status == Status.ACCEPTED;
    }
}
