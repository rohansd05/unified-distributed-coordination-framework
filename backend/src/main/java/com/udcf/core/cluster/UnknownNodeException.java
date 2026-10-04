package com.udcf.core.cluster;

/**
 * Thrown when a node id is outside the cluster.
 *
 * <p>No dedicated test: a constructor and an accessor only.</p>
 */
public class UnknownNodeException extends RuntimeException {

    private final int nodeId;

    public UnknownNodeException(int nodeId) {
        super("No node with id " + nodeId);
        this.nodeId = nodeId;
    }

    public int nodeId() {
        return nodeId;
    }
}
