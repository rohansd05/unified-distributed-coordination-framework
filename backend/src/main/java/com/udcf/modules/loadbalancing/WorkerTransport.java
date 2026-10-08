package com.udcf.modules.loadbalancing;

import java.io.IOException;

/**
 * Sends one request to one worker and waits for the answer. This is the seam between the
 * pure balancer ({@link LoadBalancer}) and the network: step E6b implements it over TCP
 * with the Exp 2 {@code RequestsClient} (link L3); tests implement it in memory.
 *
 * <p><b>Outcome mapping (E6b must follow it).</b></p>
 * <ul>
 *   <li>Return normally: the worker served the request (Exp 2 COMPLETED).</li>
 *   <li>Throw {@link WorkerDeclinedException}: the worker answered but did not serve it
 *       (Exp 2 REJECTED, queue full, or FAILED). The request is rerouted; the worker
 *       stays healthy.</li>
 *   <li>Throw {@link IOException}: the worker could not be reached, or never answered
 *       ({@code ConnectException}: nothing listening, a crashed node;
 *       {@code SocketTimeoutException}: a silent node; any other {@code IOException}: the
 *       connection closed without a reply, for example a crash mid-request). The request is
 *       rerouted and the circuit breaker marks the worker unhealthy for the rest of the run.</li>
 *   <li>Any {@link RuntimeException} is a bug in the transport, not a worker failure: it
 *       propagates to the caller of {@link LoadBalancer#dispatch}, after the balancer has
 *       released the worker's in-flight slot.</li>
 * </ul>
 *
 * <p>No dedicated test file: an interface with no behaviour.</p>
 */
@FunctionalInterface
public interface WorkerTransport {

    /**
     * Sends request {@code requestId} carrying {@code workUnits} units of work to
     * {@code worker} and blocks until it has been served.
     */
    void send(WorkerInfo worker, int requestId, int workUnits)
            throws IOException, WorkerDeclinedException;
}
