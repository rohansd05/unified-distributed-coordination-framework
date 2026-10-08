package com.udcf.modules.replication;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * What a client write on the primary did, at the moment the client was told it succeeded.
 *
 * <p>For {@link ConsistencyModel#SYNCHRONOUS} the replication future is already complete
 * when this is returned: the client waited for every backup. For
 * {@link ConsistencyModel#ASYNCHRONOUS} it completes later, after the <b>simulated</b> delay
 * and the real pushes; until then a backup may still return the old value.</p>
 *
 * <p>Covered by ReplicationRecordsTest (validation) and ReplicationNodeServiceTest.</p>
 *
 * @param item                 the version written, stamped by the primary
 * @param model                the consistency model used
 * @param localResult          what the primary's own store did with it
 * @param confirmMillis        measured time from the write call to the confirmation
 * @param simulatedDelayMillis the <b>simulated</b> (R7) delay before each asynchronous push;
 *                             always 0 for SYNCHRONOUS
 * @param backupIds            the backups pushed to, in order
 * @param replication          one outcome per backup, in {@code backupIds} order
 */
public record WriteResult(DataItem item, ConsistencyModel model, ApplyResult localResult, double confirmMillis,
                          long simulatedDelayMillis, List<Integer> backupIds,
                          CompletableFuture<List<PushOutcome>> replication) {

    public WriteResult {
        Objects.requireNonNull(item, "item must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(localResult, "localResult must not be null");
        Objects.requireNonNull(replication, "replication must not be null");
        backupIds = List.copyOf(Objects.requireNonNull(backupIds, "backupIds must not be null"));
        if (!Double.isFinite(confirmMillis) || confirmMillis < 0) {
            throw new IllegalArgumentException("confirmMillis must be finite and >= 0, was " + confirmMillis);
        }
        if (simulatedDelayMillis < 0) {
            throw new IllegalArgumentException("simulatedDelayMillis must be >= 0, was " + simulatedDelayMillis);
        }
        if (model == ConsistencyModel.SYNCHRONOUS && simulatedDelayMillis != 0) {
            throw new IllegalArgumentException("a synchronous write is never delayed");
        }
    }

    /** True if a simulated delay applies to this write's pushes (R7: show the Simulated badge). */
    public boolean simulated() {
        return simulatedDelayMillis > 0;
    }
}
