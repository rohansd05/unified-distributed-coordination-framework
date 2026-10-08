package com.udcf.modules.loadbalancing;

/**
 * The outcome of one dispatched request, as the load balancer saw it.
 *
 * <p>Ported from legacy-demos/exp06-load-balancing. Differences: {@code attempts} is new;
 * {@link #rerouted()} is derived from it (legacy also flagged a request "rerouted" when its
 * only attempt failed and nothing was retried); and a failed request carries the real time
 * spent trying (legacy reported 0). Validation is tested in DispatchResultTest.</p>
 *
 * @param requestId     sequence number within the run, at least 1
 * @param nodeId        the worker that served it, or 0 if no worker served it
 * @param latencyMillis measured at the balancer from the first attempt to the final answer,
 *                      including any failed attempts and the worker's queue wait
 * @param succeeded     true if a worker served it
 * @param attempts      workers tried; 0 when no worker was healthy at all
 */
public record DispatchResult(
        int requestId,
        int nodeId,
        double latencyMillis,
        boolean succeeded,
        int attempts
) {

    public DispatchResult {
        if (requestId < 1) {
            throw new IllegalArgumentException("requestId must be >= 1, was " + requestId);
        }
        if (latencyMillis < 0 || !Double.isFinite(latencyMillis)) {
            throw new IllegalArgumentException("latencyMillis must be finite and >= 0, was " + latencyMillis);
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must be >= 0, was " + attempts);
        }
        if (succeeded) {
            if (nodeId < 1) {
                throw new IllegalArgumentException("a served request needs nodeId >= 1, was " + nodeId);
            }
            if (attempts < 1) {
                throw new IllegalArgumentException("a served request needs attempts >= 1, was " + attempts);
            }
        } else if (nodeId != 0) {
            throw new IllegalArgumentException("a request no worker served has nodeId 0, was " + nodeId);
        }
    }

    /** True if a first choice failed or declined and another worker was tried. */
    public boolean rerouted() {
        return attempts > 1;
    }
}
