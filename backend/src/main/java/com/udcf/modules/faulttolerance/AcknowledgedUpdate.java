package com.udcf.modules.faulttolerance;

import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.modules.replication.DataItem;
import com.udcf.modules.replication.WriteResult;

import java.util.Objects;

/**
 * An update the client was told had succeeded: the evidence data loss is measured against.
 *
 * <p>Covered by FaultToleranceRecordsTest (validation) and AcknowledgedLedgerTest.</p>
 *
 * @param sequence             the client's sequence number, at least 1
 * @param item                 the version the primary stamped and confirmed
 * @param model                the consistency model of the write
 * @param simulatedDelayMillis the <b>simulated</b> (R7) delay before each asynchronous push, as
 *                             the replication service reported it; 0 for synchronous writes
 * @param acknowledgedAtNanos  when the client was told (the module's shared monotonic nano clock)
 */
public record AcknowledgedUpdate(int sequence, DataItem item, ConsistencyModel model, long simulatedDelayMillis,
                                 long acknowledgedAtNanos) {

    public AcknowledgedUpdate {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be >= 1, was " + sequence);
        }
        Objects.requireNonNull(item, "item must not be null");
        Objects.requireNonNull(model, "model must not be null");
        if (simulatedDelayMillis < 0) {
            throw new IllegalArgumentException("simulatedDelayMillis must be >= 0, was " + simulatedDelayMillis);
        }
        if (model == ConsistencyModel.SYNCHRONOUS && simulatedDelayMillis != 0) {
            throw new IllegalArgumentException("a synchronous write is never delayed");
        }
    }

    /** The acknowledgement of a confirmed write; the simulated delay is copied from the result, never assumed. */
    public static AcknowledgedUpdate from(int sequence, WriteResult result, long acknowledgedAtNanos) {
        Objects.requireNonNull(result, "result must not be null");
        return new AcknowledgedUpdate(sequence, result.item(), result.model(), result.simulatedDelayMillis(),
                acknowledgedAtNanos);
    }

    public String key() {
        return item.key();
    }

    /** True if a simulated delay applied to this write's replication (R7). */
    public boolean simulated() {
        return simulatedDelayMillis > 0;
    }
}
