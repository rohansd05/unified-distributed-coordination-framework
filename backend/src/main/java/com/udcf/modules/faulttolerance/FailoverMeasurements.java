package com.udcf.modules.faulttolerance;

import java.util.Objects;

/**
 * The measured intervals of one failover, in milliseconds (R7: every value is observed).
 *
 * <p>Each interval is computed directly from its two recorded instants; an interval whose start
 * or end was never recorded is <b>null, never 0</b>. None depends on the simulated asynchronous
 * replication delay, so none carries a simulated marker (data loss does: {@link DataLossReport}).</p>
 *
 * <p>Detection uses the shared failure detector (link L2: 700 ms heartbeat interval, 2500 ms
 * timeout). From those settings detection is <b>estimated</b> at about 1.8 to 3.2 s after the
 * crash; E8c measures it.</p>
 *
 * <p>Differences from the legacy {@code FailoverMetrics}: failover is measured from detection
 * (legacy: from the crash); "service restored" from the promotion (legacy "recovery": crash to
 * first write); outage and recovery are new; incomplete intervals are null (legacy: 0); values are
 * not rounded.</p>
 *
 * <p>Covered by FailoverMeasurementsTest.</p>
 *
 * @param detectionMillis       crash instant to detection instant (the first suspicion by any live node)
 * @param failoverMillis        detection instant to the new primary promoted
 * @param serviceRestoredMillis new primary promoted to its first accepted write
 * @param outageMillis          crash instant to the first accepted write by the new primary, from
 *                              those two instants (not the sum of the other intervals)
 * @param recoveryMillis        old primary recovered to demoted <b>and</b> resynchronised (the later of the two)
 */
public record FailoverMeasurements(Double detectionMillis, Double failoverMillis, Double serviceRestoredMillis,
                                   Double outageMillis, Double recoveryMillis) {

    private static final double NANOS_PER_MILLI = 1_000_000d;

    public FailoverMeasurements {
        requireInterval("detectionMillis", detectionMillis);
        requireInterval("failoverMillis", failoverMillis);
        requireInterval("serviceRestoredMillis", serviceRestoredMillis);
        requireInterval("outageMillis", outageMillis);
        requireInterval("recoveryMillis", recoveryMillis);
    }

    /**
     * The intervals of {@code run}.
     *
     * @throws IllegalArgumentException if an interval would be negative (an instant recorded
     *                                  before the one it follows)
     */
    public static FailoverMeasurements of(FailoverRun run) {
        Objects.requireNonNull(run, "run must not be null");
        Long recoveryEnd = run.demotedAtNanos() == null || run.resyncedAtNanos() == null
                ? null
                : Long.valueOf(Math.max(run.demotedAtNanos(), run.resyncedAtNanos()));
        return new FailoverMeasurements(
                between("detection", run.crashAtNanos(), run.detectedAtNanos()),
                between("failover", run.detectedAtNanos(), run.promotedAtNanos()),
                between("service restored", run.promotedAtNanos(), run.restoredAtNanos()),
                between("outage", run.crashAtNanos(), run.restoredAtNanos()),
                between("recovery", run.oldPrimaryRecoveredAtNanos(), recoveryEnd));
    }

    private static Double between(String name, Long startNanos, Long endNanos) {
        if (startNanos == null || endNanos == null) {
            return null;
        }
        if (endNanos < startNanos) {
            throw new IllegalArgumentException("the " + name + " interval would be negative: ends at "
                    + endNanos + " ns, before its start at " + startNanos + " ns");
        }
        return (endNanos - startNanos) / NANOS_PER_MILLI;
    }

    private static void requireInterval(String name, Double millis) {
        if (millis != null && (!Double.isFinite(millis) || millis < 0)) {
            throw new IllegalArgumentException(name + " must be null or finite and >= 0, was " + millis);
        }
    }
}
