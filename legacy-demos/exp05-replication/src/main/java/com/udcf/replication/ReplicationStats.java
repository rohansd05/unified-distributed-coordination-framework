package com.udcf.replication;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-backup replication health, measured rather than estimated.
 *
 * <p>Every figure here comes from an actual acknowledgement round trip or an actual
 * failure. The dashboard in the full framework reads exactly these values for its
 * replication latency and failure panels.</p>
 */
public class ReplicationStats {

    private final int backupNodeId;
    private final List<Double> latenciesMillis = new ArrayList<>();
    private int failures;
    private int staleRejections;
    private long lastSyncMillis;

    public ReplicationStats(int backupNodeId) {
        this.backupNodeId = backupNodeId;
    }

    /** Records a successful acknowledgement and the round-trip time it took. */
    public synchronized void recordSuccess(double millis) {
        latenciesMillis.add(millis);
        lastSyncMillis = System.currentTimeMillis();
    }

    /** Records a backup that could not be reached at all. */
    public synchronized void recordFailure() {
        failures++;
    }

    /** Records an update the backup accepted the message for but refused as stale. */
    public synchronized void recordStaleRejection() {
        staleRejections++;
    }

    public synchronized double averageLatencyMillis() {
        if (latenciesMillis.isEmpty()) {
            return 0d;
        }
        double total = 0;
        for (double v : latenciesMillis) {
            total += v;
        }
        return round(total / latenciesMillis.size());
    }

    public synchronized double maxLatencyMillis() {
        double max = 0;
        for (double v : latenciesMillis) {
            max = Math.max(max, v);
        }
        return round(max);
    }

    public synchronized int successCount()     { return latenciesMillis.size(); }
    public synchronized int failureCount()     { return failures; }
    public synchronized int staleCount()       { return staleRejections; }
    public int backupNodeId()                  { return backupNodeId; }

    public synchronized String lastSyncDescription() {
        if (lastSyncMillis == 0) {
            return "never";
        }
        long ago = System.currentTimeMillis() - lastSyncMillis;
        return ago + " ms ago";
    }

    private static double round(double v) {
        return Math.round(v * 100d) / 100d;
    }
}
