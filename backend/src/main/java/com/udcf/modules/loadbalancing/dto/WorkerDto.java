package com.udcf.modules.loadbalancing.dto;

import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.cluster.NodeStatus;

/**
 * One worker as the balancer sees it, with the current or last run's live counters.
 *
 * <p>No dedicated test: a record; built and tested through LoadBalancingModuleTest.</p>
 *
 * @param weight               the static weight weighted round robin uses (the thread count)
 * @param healthy              false if the node is down, or the breaker took it out of the last run
 * @param ewmaLatencyMillis    null until it has served a request
 * @param averageLatencyMillis null until it has served a request
 */
public record WorkerDto(
        int nodeId,
        NodeStatus nodeStatus,
        NodeCapacity capacity,
        int threads,
        int workMultiplier,
        int weight,
        int port,
        boolean healthy,
        int inFlight,
        int completed,
        int failed,
        int declined,
        Double ewmaLatencyMillis,
        Double averageLatencyMillis
) {
}
