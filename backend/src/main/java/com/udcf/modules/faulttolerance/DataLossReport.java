package com.udcf.modules.faulttolerance;

import java.util.List;
import java.util.Objects;

/**
 * How many updates the client was told had succeeded are missing from the new primary
 * ({@link DataLoss#measure}).
 *
 * <p>When the loss cannot be assessed, {@code lost} and the per-model counts are null (never 0)
 * and {@code notAssessed} says why. Synchronous writes are expected to lose nothing.</p>
 *
 * <p><b>Simulated marker (R7).</b> {@code simulated} is true when any acknowledged write was
 * asynchronous with a simulated replication delay; the loss of asynchronous writes depends on
 * that delay, which stands in for network latency. {@code simulatedDelayMillis} is the delay the
 * replication service reported (the largest, if it changed between writes), never a typed value.</p>
 *
 * <p>Covered by FaultToleranceRecordsTest (validation) and DataLossTest.</p>
 *
 * @param acknowledged         acknowledged updates checked
 * @param lost                 acknowledged updates the new primary does not hold at their confirmed
 *                             version or newer; null if not assessed
 * @param lostKeys             their keys, in sequence order; empty if not assessed
 * @param lostSynchronous      of {@code lost}, synchronous writes; null if not assessed
 * @param lostAsynchronous     of {@code lost}, asynchronous writes; null if not assessed
 * @param simulated            true if any acknowledged write carried a simulated delay
 * @param simulatedDelayMillis the largest simulated delay among them, 0 if none
 * @param notAssessed          why the loss could not be assessed, or null if it was
 */
public record DataLossReport(int acknowledged, Integer lost, List<String> lostKeys, Integer lostSynchronous,
                             Integer lostAsynchronous, boolean simulated, long simulatedDelayMillis,
                             NotAssessed notAssessed) {

    /** Why data loss could not be assessed. */
    public enum NotAssessed {

        /** Nothing was acknowledged yet, so there is nothing that could have been lost. */
        NOTHING_ACKNOWLEDGED,

        /** There is no primary store to check against (no primary, or it is down). */
        NO_PRIMARY_STORE
    }

    public DataLossReport {
        if (acknowledged < 0) {
            throw new IllegalArgumentException("acknowledged must be >= 0, was " + acknowledged);
        }
        lostKeys = List.copyOf(Objects.requireNonNull(lostKeys, "lostKeys must not be null"));
        if (simulatedDelayMillis < 0 || simulated != (simulatedDelayMillis > 0)) {
            throw new IllegalArgumentException("simulated must be true exactly when simulatedDelayMillis > 0, was "
                    + simulatedDelayMillis);
        }
        if (notAssessed != null) {
            if (lost != null || lostSynchronous != null || lostAsynchronous != null || !lostKeys.isEmpty()) {
                throw new IllegalArgumentException("a loss that was not assessed has no counts");
            }
        } else {
            if (lost == null || lostSynchronous == null || lostAsynchronous == null) {
                throw new IllegalArgumentException("an assessed loss has every count");
            }
            if (lost != lostKeys.size() || lost != lostSynchronous + lostAsynchronous || lost > acknowledged) {
                throw new IllegalArgumentException("inconsistent counts: lost " + lost + ", keys " + lostKeys.size()
                        + ", synchronous " + lostSynchronous + ", asynchronous " + lostAsynchronous
                        + ", acknowledged " + acknowledged);
            }
        }
    }

    /** True if the loss was assessed. */
    public boolean assessed() {
        return notAssessed == null;
    }
}
