package com.udcf.modules.rmi;

import java.io.Serial;
import java.io.Serializable;

/**
 * The caller's identity and Lamport time, sent as the first argument of every
 * {@link NodeEndpoint} call (link L4).
 *
 * <p>The caller is always a cluster node with its own shared clock: there is no cluster-level
 * client in Experiment 1, so {@code senderNodeId} is at least 1.</p>
 *
 * @param senderNodeId   the calling node
 * @param senderLamport  the caller's Lamport time when it sent the call (after its tick)
 */
public record CallContext(int senderNodeId, long senderLamport) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public CallContext {
        if (senderNodeId < 1) {
            throw new IllegalArgumentException("senderNodeId must be >= 1, was " + senderNodeId);
        }
        if (senderLamport < 0) {
            throw new IllegalArgumentException("senderLamport must be >= 0, was " + senderLamport);
        }
    }
}
