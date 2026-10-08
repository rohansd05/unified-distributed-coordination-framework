package com.udcf.modules.loadbalancing.dto;

/**
 * A run's crash plan and what happened to it.
 *
 * <p>No dedicated test: a record without behaviour.</p>
 *
 * @param crashed true only if the module's {@code Cluster.crash(nodeId)} call crashed the node;
 *                false while the run has not reached {@code afterServed}, or if the node was
 *                already down by then
 */
public record CrashDto(int nodeId, int afterServed, boolean crashed) {
}
