package com.udcf.web;

/**
 * Thrown when a node is asked to enter the state it is already in (crash a crashed node,
 * recover an up node). Mapped to HTTP 409.
 *
 * <p>No dedicated test: a constructor and an accessor only.</p>
 */
public class NodeStateConflictException extends RuntimeException {

    private final int nodeId;

    public NodeStateConflictException(int nodeId, String message) {
        super(message);
        this.nodeId = nodeId;
    }

    public int nodeId() {
        return nodeId;
    }
}
