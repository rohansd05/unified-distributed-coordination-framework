package com.udcf.modules.faulttolerance;

/**
 * How hard the update client tries to get one update confirmed through a failover.
 *
 * <p>The retry window is about {@code maxAttempts x retryDelayMillis}. The legacy demo used 14
 * attempts and 120 ms (about 1.7 s), which is shorter than the shared detector's estimated
 * detection time (1.8 to 3.2 s), so the module must size both in YAML to cover a whole failover,
 * or the stream gives up during every failover.</p>
 *
 * <p>Ported from the legacy {@code UpdateClient.sendUpdate}, without sockets or sleeps: the
 * policy only decides ({@link UpdateAttempt}), the caller sends and waits.</p>
 *
 * <p>Covered by UpdateRetryPolicyTest. Thread safety: immutable.</p>
 *
 * @param maxAttempts      sends plus discoveries allowed for one update, at least 1
 * @param retryDelayMillis wait before a discovery after a failed attempt, at least 0
 */
public record UpdateRetryPolicy(int maxAttempts, long retryDelayMillis) {

    public UpdateRetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, was " + maxAttempts);
        }
        if (retryDelayMillis < 0) {
            throw new IllegalArgumentException("retryDelayMillis must be >= 0, was " + retryDelayMillis);
        }
    }

    /**
     * Starts one update.
     *
     * @param knownPrimaryId the primary the client believes in, or null to discover it first
     */
    public UpdateAttempt begin(Integer knownPrimaryId) {
        return new UpdateAttempt(this, knownPrimaryId);
    }
}
