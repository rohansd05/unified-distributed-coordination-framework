package com.udcf.modules.rmi;

import java.io.Serial;
import java.util.Objects;

/**
 * Reply to {@link NodeEndpoint#nodeInfo}. These six fields are the whole record: no host name,
 * user name, path or other machine-identifying data ever crosses the wire.
 *
 * @param nodeId          the node that answered
 * @param lamportTime     its Lamport time when it replied
 * @param registryPort    the port of the node's RMI registry
 * @param capacityProfile the node's capacity profile name (FAST, MEDIUM or SLOW); a string, so
 *                        no enum crosses RMI
 * @param uptimeMillis    time since this endpoint started on the node, measured
 * @param javaVersion     the JVM's version number ({@code Runtime.version()})
 */
public record NodeInfo(
        int nodeId,
        long lamportTime,
        int registryPort,
        String capacityProfile,
        long uptimeMillis,
        String javaVersion
) implements EndpointReply {

    @Serial
    private static final long serialVersionUID = 1L;

    public NodeInfo {
        EndpointReply.requireStamp(nodeId, lamportTime);
        if (registryPort < 1 || registryPort > 65535) {
            throw new IllegalArgumentException("registryPort must be 1-65535, was " + registryPort);
        }
        Objects.requireNonNull(capacityProfile, "capacityProfile must not be null");
        if (uptimeMillis < 0) {
            throw new IllegalArgumentException("uptimeMillis must be >= 0, was " + uptimeMillis);
        }
        Objects.requireNonNull(javaVersion, "javaVersion must not be null");
    }
}
