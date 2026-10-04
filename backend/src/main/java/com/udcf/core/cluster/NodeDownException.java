package com.udcf.core.cluster;

/**
 * Thrown when an operation needs a node that is crashed.
 *
 * <p>No dedicated test: a constructor and an accessor only.</p>
 */
public class NodeDownException extends RuntimeException {

    private final int nodeId;

    public NodeDownException(int nodeId) {
        super("Node " + nodeId + " is crashed");
        this.nodeId = nodeId;
    }

    public int nodeId() {
        return nodeId;
    }
}
