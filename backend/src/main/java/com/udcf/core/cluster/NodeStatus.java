package com.udcf.core.cluster;

/**
 * Whether a node is alive on every protocol or crashed on every protocol (R10).
 *
 * <p>No dedicated test: a plain enum with no behaviour.</p>
 */
public enum NodeStatus {
    UP,
    CRASHED
}
