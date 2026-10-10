package com.udcf.modules.rmi;

import java.io.Serial;
import java.util.Objects;

/**
 * Reply to {@link NodeEndpoint#echo}.
 *
 * @param nodeId      the node that answered
 * @param lamportTime its Lamport time when it replied
 * @param text        the text it received, unchanged
 */
public record EchoReply(int nodeId, long lamportTime, String text) implements EndpointReply {

    @Serial
    private static final long serialVersionUID = 1L;

    public EchoReply {
        EndpointReply.requireStamp(nodeId, lamportTime);
        Objects.requireNonNull(text, "text must not be null");
    }
}
