package com.udcf.fault;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Timings and counts for one crash-and-failover run. Every value is observed, not assumed.
 *
 * <p>The three durations are the numbers a fault-tolerance report is actually judged on:
 * how long the cluster took to notice, how long it took to appoint a replacement, and how
 * long until it was serving updates again.</p>
 */
public class FailoverMetrics {

    private final ConsistencyModel model;

    private long crashNanos;
    private long detectNanos;
    private long promoteNanos;
    private long serviceRestoredNanos;

    private int oldPrimary;
    private int newPrimary;
    private long newEpoch;

    private int attempted;
    private int confirmed;
    private int rejectedDuringOutage;

    /** Sequence numbers the client was told had succeeded. */
    private final Set<String> confirmedKeys = Collections.synchronizedSet(new LinkedHashSet<>());

    public FailoverMetrics(ConsistencyModel model) {
        this.model = model;
    }

    public void markCrash(int oldPrimaryId) {
        crashNanos = System.nanoTime();
        oldPrimary = oldPrimaryId;
    }

    public void markDetected() {
        if (detectNanos == 0) {
            detectNanos = System.nanoTime();
        }
    }

    public void markPromoted(int newPrimaryId, long epoch) {
        if (promoteNanos == 0) {
            promoteNanos = System.nanoTime();
            newPrimary = newPrimaryId;
            newEpoch = epoch;
        }
    }

    public void markServiceRestored() {
        if (serviceRestoredNanos == 0) {
            serviceRestoredNanos = System.nanoTime();
        }
    }

    public synchronized void countAttempt()            { attempted++; }
    public synchronized void countRejected()           { rejectedDuringOutage++; }
    public synchronized void countConfirmed(String key) {
        confirmed++;
        confirmedKeys.add(key);
    }

    /** Milliseconds from the crash until some node first suspected it. */
    public double detectionMillis()        { return millis(crashNanos, detectNanos); }

    /** Milliseconds from the crash until a replacement primary existed. */
    public double failoverMillis()         { return millis(crashNanos, promoteNanos); }

    /** Milliseconds from the crash until a client update succeeded again. */
    public double recoveryMillis()         { return millis(crashNanos, serviceRestoredNanos); }

    private static double millis(long from, long to) {
        if (from == 0 || to == 0) {
            return 0d;
        }
        return Math.round((to - from) / 1_000_000d * 10d) / 10d;
    }

    public ConsistencyModel model()        { return model; }
    public int oldPrimary()                { return oldPrimary; }
    public int newPrimary()                { return newPrimary; }
    public long newEpoch()                 { return newEpoch; }
    public int attempted()                 { return attempted; }
    public int confirmed()                 { return confirmed; }
    public int rejectedDuringOutage()      { return rejectedDuringOutage; }
    public Set<String> confirmedKeys()     { return confirmedKeys; }
}
