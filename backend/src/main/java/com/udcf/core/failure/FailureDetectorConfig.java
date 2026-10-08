package com.udcf.core.failure;

/**
 * Timing of the shared failure detector (link L2).
 *
 * <p>Tested in FailureDetectorTest (validation only).</p>
 *
 * @param intervalMillis how often a node sends a heartbeat to every peer and checks for silence
 * @param timeoutMillis  silence longer than this makes a peer suspected; must exceed the interval
 */
public record FailureDetectorConfig(long intervalMillis, long timeoutMillis) {

    public FailureDetectorConfig {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("intervalMillis must be > 0, was " + intervalMillis);
        }
        if (timeoutMillis <= intervalMillis) {
            throw new IllegalArgumentException("timeoutMillis must be greater than intervalMillis ("
                    + intervalMillis + "), was " + timeoutMillis);
        }
    }
}
