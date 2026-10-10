package com.udcf.modules.rmi;

import java.io.Serial;

/**
 * Reply to {@link NodeEndpoint#ping}.
 *
 * @param nodeId      the node that answered
 * @param lamportTime its Lamport time when it replied
 */
public record PingReply(int nodeId, long lamportTime) implements EndpointReply {

    @Serial
    private static final long serialVersionUID = 1L;

    public PingReply {
        EndpointReply.requireStamp(nodeId, lamportTime);
    }
}
