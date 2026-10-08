package com.udcf.core.failure;

/**
 * Reacts to a {@link FailureDetector}'s state changes, for example election (Exp 4) starting a
 * new election or fault tolerance (Exp 8) failing over.
 *
 * <p>Called on the thread that drives the detector, never while it holds its lock. On the
 * election channel that is the node's election worker thread, never the UDP listener. A
 * listener must not block.</p>
 *
 * <p>No dedicated test: an interface. FailureDetectorTest exercises it.</p>
 */
public interface FailureListener {

    /** No heartbeat from {@code peerId} for {@code silentMillis}, more than the timeout. */
    void onSuspected(int peerId, long silentMillis);

    /** A heartbeat arrived from a suspected peer after {@code silentMillis} of silence. */
    void onAlive(int peerId, long silentMillis);
}
