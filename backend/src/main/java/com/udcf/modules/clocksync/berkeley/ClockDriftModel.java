package com.udcf.modules.clocksync.berkeley;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Pure model of a node's physical clock with configurable simulated drift.
 *
 * <p>In a real distributed system, physical quartz clocks drift due to temperature and
 * crystal frequency variations. Since all UDCF nodes reside on one host machine, physical
 * clock drift is simulated (R7, R11).</p>
 *
 * <p><b>Drift model:</b></p>
 * <pre>
 *   offset(t) = baseOffset + (driftRateMsPerSec * deltaSeconds)
 *   simulatedTime(t) = referenceWallTime(t) + offset(t)
 * </pre>
 *
 * <p>When a Berkeley adjustment is applied, the base offset is updated:
 * {@code baseOffset = baseOffset + adjustment}, and the reference anchor is reset to the current time.</p>
 */
public class ClockDriftModel {

    private final int nodeId;
    private long baseOffsetMillis;
    private double driftRateMsPerSec;
    private Instant lastAnchorTime;

    public ClockDriftModel(int nodeId) {
        this(nodeId, 0L, 0.0, Instant.now());
    }

    public ClockDriftModel(int nodeId, long initialOffsetMillis, double driftRateMsPerSec) {
        this(nodeId, initialOffsetMillis, driftRateMsPerSec, Instant.now());
    }

    public ClockDriftModel(int nodeId, long initialOffsetMillis, double driftRateMsPerSec, Instant initialAnchorTime) {
        if (nodeId < 1) {
            throw new IllegalArgumentException("nodeId must be >= 1, was " + nodeId);
        }
        this.nodeId = nodeId;
        this.baseOffsetMillis = initialOffsetMillis;
        this.driftRateMsPerSec = driftRateMsPerSec;
        this.lastAnchorTime = Objects.requireNonNull(initialAnchorTime, "initialAnchorTime must not be null");
    }

    public int nodeId() {
        return nodeId;
    }

    /**
     * Calculates the current simulated offset in milliseconds at the given reference time.
     */
    public synchronized long currentOffsetMillis(Instant referenceTime) {
        Objects.requireNonNull(referenceTime, "referenceTime must not be null");
        long elapsedMillis = Duration.between(lastAnchorTime, referenceTime).toMillis();
        long accumulatedDrift = Math.round(driftRateMsPerSec * (elapsedMillis / 1000.0));
        return baseOffsetMillis + accumulatedDrift;
    }

    /**
     * Calculates the simulated physical time at the given reference time.
     */
    public synchronized Instant simulatedTime(Instant referenceTime) {
        long offset = currentOffsetMillis(referenceTime);
        return referenceTime.plusMillis(offset);
    }

    /**
     * Applies an adjustment calculated by the Berkeley daemon, shifting the clock offset
     * and resetting the anchor to {@code referenceTime}.
     */
    public synchronized void applyAdjustment(long adjustmentMillis, Instant referenceTime) {
        long current = currentOffsetMillis(referenceTime);
        this.baseOffsetMillis = current + adjustmentMillis;
        this.lastAnchorTime = Objects.requireNonNull(referenceTime, "referenceTime must not be null");
    }

    /**
     * Reconfigures the base offset directly.
     */
    public synchronized void setOffsetMillis(long offsetMillis, Instant referenceTime) {
        this.baseOffsetMillis = offsetMillis;
        this.lastAnchorTime = Objects.requireNonNull(referenceTime, "referenceTime must not be null");
    }

    /**
     * Reconfigures the drift rate in ms/s.
     */
    public synchronized void setDriftRateMsPerSec(double driftRateMsPerSec, Instant referenceTime) {
        long current = currentOffsetMillis(referenceTime);
        this.baseOffsetMillis = current;
        this.driftRateMsPerSec = driftRateMsPerSec;
        this.lastAnchorTime = Objects.requireNonNull(referenceTime, "referenceTime must not be null");
    }

    public synchronized double driftRateMsPerSec() {
        return driftRateMsPerSec;
    }

    /**
     * Captures a point-in-time snapshot with {@code simulated: true} (R7).
     */
    public synchronized NodeDriftSnapshot snapshot(Instant referenceTime) {
        long offset = currentOffsetMillis(referenceTime);
        Instant simTime = referenceTime.plusMillis(offset);
        return NodeDriftSnapshot.of(nodeId, offset, driftRateMsPerSec, simTime);
    }

    /**
     * Resets offset and drift back to 0.
     */
    public synchronized void reset(Instant referenceTime) {
        reset(0L, 0.0, referenceTime);
    }

    /**
     * Resets offset and drift back to specified configured values.
     */
    public synchronized void reset(long initialOffsetMillis, double driftRateMsPerSec, Instant referenceTime) {
        this.baseOffsetMillis = initialOffsetMillis;
        this.driftRateMsPerSec = driftRateMsPerSec;
        this.lastAnchorTime = Objects.requireNonNull(referenceTime, "referenceTime must not be null");
    }
}
