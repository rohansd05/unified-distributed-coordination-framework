package com.udcf.core.cluster;

/**
 * The 127.0.0.1 ports one node binds, one per protocol endpoint (docs/HANDOFF.md 6.3).
 *
 * <p>No dedicated test: a plain record. ClusterPropertiesTest checks how it is derived.</p>
 */
public record NodePorts(
        int rmi,
        int clock,
        int election,
        int replication,
        int requests,
        int mapreduce
) {
}
