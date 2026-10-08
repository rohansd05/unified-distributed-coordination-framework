package com.udcf.core.failure;

/**
 * Sends one heartbeat to a peer over the owner's transport (the election UDP socket, link L2).
 *
 * <p>Must not block. A failure may be thrown; the detector logs it and carries on with the
 * other peers.</p>
 *
 * <p>No dedicated test: an interface. FailureDetectorTest exercises it.</p>
 */
@FunctionalInterface
public interface HeartbeatSender {

    void sendHeartbeat(int peerId);
}
