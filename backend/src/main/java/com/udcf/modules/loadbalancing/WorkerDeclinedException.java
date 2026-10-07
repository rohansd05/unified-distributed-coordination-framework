package com.udcf.modules.loadbalancing;

/**
 * Thrown by a {@link WorkerTransport} when the worker is alive and answered, but did not
 * serve the request: for example its bounded queue was full (Exp 2 REJECTED) or the work
 * failed on it (Exp 2 FAILED).
 *
 * <p>The balancer reroutes the request to another worker but does <b>not</b> trip the
 * circuit breaker, because the worker is reachable; only an {@link java.io.IOException}
 * marks a worker unhealthy.</p>
 *
 * <p>No dedicated test file: a plain exception with no behaviour. Its effect on dispatch
 * is tested in LoadBalancerTest.</p>
 */
public class WorkerDeclinedException extends Exception {

    public WorkerDeclinedException(String message) {
        super(message);
    }
}
