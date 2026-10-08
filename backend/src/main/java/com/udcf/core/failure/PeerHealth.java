package com.udcf.core.failure;

/**
 * One peer as a {@link FailureDetector} currently sees it.
 *
 * <p>No dedicated test: a plain record. FailureDetectorTest checks how it is filled.</p>
 *
 * @param peerId                   the peer
 * @param suspected                whether the peer is currently suspected
 * @param millisSinceLastHeartbeat time since its last heartbeat; {@code null} if none has been
 *                                 heard since the detector started (unmeasured, never 0, R7)
 */
public record PeerHealth(int peerId, boolean suspected, Long millisSinceLastHeartbeat) {
}
