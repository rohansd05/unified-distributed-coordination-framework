package com.udcf.modules.rmi;

import java.io.Serializable;

/**
 * What every {@link NodeEndpoint} reply carries: the answering node and its Lamport time when it
 * sent the reply, so the caller can apply the receive rule (link L4).
 *
 * <p>No dedicated test: a sealed marker interface (R6). The records are covered by
 * RmiRecordsTest.</p>
 */
public sealed interface EndpointReply extends Serializable
        permits PingReply, NodeInfo, EchoReply, HashResult {

    /** The node that answered. */
    int nodeId();

    /** The answering node's Lamport time when it sent this reply. */
    long lamportTime();

    /** Shared validation of the two common fields. */
    static void requireStamp(int nodeId, long lamportTime) {
        if (nodeId < 1) {
            throw new IllegalArgumentException("nodeId must be >= 1, was " + nodeId);
        }
        if (lamportTime < 0) {
            throw new IllegalArgumentException("lamportTime must be >= 0, was " + lamportTime);
        }
    }
}
