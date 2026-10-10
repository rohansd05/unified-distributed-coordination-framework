package com.udcf.modules.rmi;

import com.udcf.core.cluster.Cluster;

import java.util.Objects;

/**
 * The {@link CallPath#LOCAL} lookup: the node's exported {@link NodeEndpoint} object itself, so a
 * call is a direct Java call (no stub, no registry, nothing marshalled) running the same code as
 * a remote call.
 *
 * <p>A dead process computes nothing, so a node whose RMI service is not running (never started,
 * stopped or crashed) gives {@link IllegalStateException} "node N is not running". That is a
 * state error, not an RMI failure: step E1c checks the node's state first and answers 409. An
 * unknown id gives {@code UnknownNodeException}.</p>
 */
public final class LocalEndpointLookup implements EndpointLookup {

    private final Cluster cluster;

    public LocalEndpointLookup(Cluster cluster) {
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
    }

    @Override
    public NodeEndpoint lookup(int nodeId) {
        return RmiNodeService.find(cluster.node(nodeId))
                .orElseThrow(() -> new IllegalStateException("node " + nodeId + " is not running"))
                .localEndpoint();
    }

    @Override
    public CallPath path() {
        return CallPath.LOCAL;
    }
}
