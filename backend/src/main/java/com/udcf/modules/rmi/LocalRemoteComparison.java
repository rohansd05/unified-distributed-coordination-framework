package com.udcf.modules.rmi;

import java.util.Objects;

/**
 * The same call made directly on the endpoint object (LOCAL) and through RMI (REMOTE), side by
 * side.
 *
 * <p><b>Read it honestly.</b> Each side is a single measurement, not an average. The first call
 * in a fresh JVM includes class loading and provider lookup (for example the SHA-256 provider),
 * and the local side runs first, so it can be the slower one. The overhead can therefore be
 * negative and the ratio below 1. Nothing is clamped, smoothed or warmed up behind the user's
 * back (R7).</p>
 *
 * <p>Every figure is computed only from measured values: a side that did not succeed has a null
 * time, and the overhead and ratio are then null too. The ratio is also null when the local time
 * is 0 (it would be undefined).</p>
 *
 * @param method        the method both sides called
 * @param targetNodeId  the node both sides called
 * @param localNanos    the LOCAL round trip; null if it did not succeed
 * @param remoteNanos   the REMOTE round trip; null if it did not succeed
 * @param overheadNanos {@code remoteNanos - localNanos}; may be negative; null unless both are known
 * @param ratio         {@code remoteNanos / localNanos}; may be below 1; null unless both are known
 *                      and the local time is above 0
 * @param digestsMatch  for COMPUTE_HASH with both sides successful, whether the two digests are
 *                      equal; null otherwise
 */
public record LocalRemoteComparison(
        RmiMethod method,
        int targetNodeId,
        Long localNanos,
        Long remoteNanos,
        Long overheadNanos,
        Double ratio,
        Boolean digestsMatch
) {

    public LocalRemoteComparison {
        Objects.requireNonNull(method, "method must not be null");
        if (targetNodeId < 1) {
            throw new IllegalArgumentException("targetNodeId must be >= 1, was " + targetNodeId);
        }
    }

    /** Compares a LOCAL and a REMOTE result of the same method on the same node. */
    public static LocalRemoteComparison of(RmiCallResult local, RmiCallResult remote) {
        Objects.requireNonNull(local, "local must not be null");
        Objects.requireNonNull(remote, "remote must not be null");
        if (local.path() != CallPath.LOCAL || remote.path() != CallPath.REMOTE) {
            throw new IllegalArgumentException("compare a LOCAL result with a REMOTE one, got "
                    + local.path() + " and " + remote.path());
        }
        if (local.method() != remote.method() || local.targetNodeId() != remote.targetNodeId()) {
            throw new IllegalArgumentException("both sides must call the same method on the same node");
        }
        Long localNanos = local.succeeded() ? local.roundTripNanos() : null;
        Long remoteNanos = remote.succeeded() ? remote.roundTripNanos() : null;
        boolean both = localNanos != null && remoteNanos != null;
        Long overhead = both ? remoteNanos - localNanos : null;
        Double ratio = both && localNanos > 0 ? (double) remoteNanos / localNanos : null;
        Boolean digestsMatch = null;
        if (local.reply() instanceof HashResult l && remote.reply() instanceof HashResult r) {
            digestsMatch = l.digestHex().equals(r.digestHex());
        }
        return new LocalRemoteComparison(local.method(), local.targetNodeId(), localNanos, remoteNanos, overhead,
                ratio, digestsMatch);
    }
}
