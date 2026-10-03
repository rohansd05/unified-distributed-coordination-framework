package com.udcf.loadbalancer;

/**
 * The outcome of one dispatched request, as the load balancer saw it.
 *
 * @param requestId     sequence number within the phase
 * @param nodeId        worker the balancer chose, or 0 if every worker was unreachable
 * @param latencyMillis full round trip measured at the balancer, including queue wait
 * @param succeeded     false if the worker could not be reached at all
 * @param rerouted      true if a first choice failed and the request was retried elsewhere
 */
public record DispatchResult(
        int requestId,
        int nodeId,
        double latencyMillis,
        boolean succeeded,
        boolean rerouted
) {
}
