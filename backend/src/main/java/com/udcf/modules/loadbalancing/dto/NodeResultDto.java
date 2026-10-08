package com.udcf.modules.loadbalancing.dto;

import com.udcf.core.cluster.NodeCapacity;

/**
 * One worker's share of one run.
 *
 * <p>No dedicated test: a record; built and tested through LoadBalancingModuleTest.</p>
 *
 * @param requests             requests this worker served
 * @param averageLatencyMillis mean balancer-measured latency of those requests; null if none
 * @param failedAttempts       attempts that could not reach it (each tripped the breaker)
 * @param declinedAttempts     attempts it answered without serving
 * @param healthy              the balancer's view at the end of the run (false once the breaker tripped)
 */
public record NodeResultDto(
        int nodeId,
        NodeCapacity capacity,
        int requests,
        Double averageLatencyMillis,
        int failedAttempts,
        int declinedAttempts,
        boolean healthy
) {
}
