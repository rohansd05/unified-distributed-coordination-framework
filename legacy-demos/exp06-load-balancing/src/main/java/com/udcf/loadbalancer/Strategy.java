package com.udcf.loadbalancer;

/**
 * The four load balancing algorithms compared in Experiment 6.
 *
 * <p>All four answer the same question — which worker should serve the next request —
 * but they use different information to answer it, and that is the whole experiment.
 * The workers in this demonstration deliberately have unequal capacity, because on
 * identical workers every algorithm produces the same result and the comparison would
 * prove nothing.</p>
 */
public enum Strategy {

    /**
     * Take the next worker in order, repeating forever.
     *
     * <p>Uses no information at all beyond its own position in the list. Perfectly fair
     * by request count, which is exactly the problem: a slow worker receives the same
     * number of requests as a fast one and becomes a bottleneck.</p>
     */
    ROUND_ROBIN,

    /**
     * Round robin biased by a configured capacity weight.
     *
     * <p>Uses static information known in advance. A worker with weight 4 receives four
     * requests for every one that goes to a worker with weight 1. Better than plain round
     * robin when capacities differ, but blind to what is actually happening right now.</p>
     */
    WEIGHTED_ROUND_ROBIN,

    /**
     * Send to whichever worker currently has the fewest requests in flight.
     *
     * <p>Uses live information. The balancer counts the requests it has dispatched but
     * not yet had answered, so a worker that is falling behind naturally stops being
     * chosen without anyone having to configure a weight.</p>
     */
    LEAST_CONNECTIONS,

    /**
     * Send to the worker with the lowest estimated time to completion.
     *
     * <p>Uses live information plus measured history: the worker's recent average
     * response time multiplied by its outstanding work. This reacts to a worker that has
     * become slow for any reason, not only one that is configured to be slow.</p>
     */
    LEAST_RESPONSE_TIME
}
