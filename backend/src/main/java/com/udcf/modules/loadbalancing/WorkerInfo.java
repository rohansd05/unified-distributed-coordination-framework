package com.udcf.modules.loadbalancing;

import com.udcf.core.cluster.NodeCapacity;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The load balancer's live view of one worker node.
 *
 * <p>Everything here is measured by the balancer itself as requests go out and replies
 * come back. Nothing is reported by the worker and nothing is assumed, which is what
 * lets the balancer react to a worker that has become slow rather than one that was
 * merely configured to be slow.</p>
 *
 * <p>Ported from legacy-demos/exp06-load-balancing. Differences from the legacy class:</p>
 * <ul>
 *   <li>The EWMA counts its samples instead of treating 0.0 as "no sample yet", so a
 *       0 ms reply does not re-seed it.</li>
 *   <li>The latency fields are updated under this object's lock, so two replies arriving
 *       at once cannot lose an EWMA update.</li>
 *   <li>Averages are {@link OptionalDouble} and empty before the first reply (legacy
 *       returned 0), and nothing is rounded here; presentation rounds.</li>
 *   <li>{@link #resetCounters()} does not reset the in-flight count: it is live state
 *       (requests really outstanding), so clearing it while requests are in flight would
 *       drive it negative when they finish.</li>
 *   <li>A separate "declined" count for workers that answered without serving.</li>
 *   <li>The mutators are package-private: only {@link LoadBalancer} changes this state, so
 *       selection and the in-flight increment stay one atomic step.</li>
 * </ul>
 */
public class WorkerInfo {

    /**
     * Weight of the newest sample in the response-time average. 0.3 keeps the estimate
     * responsive without letting one slow request dominate it (HANDOFF Appendix B, Exp 6).
     */
    public static final double EWMA_ALPHA = 0.3d;

    private final int nodeId;
    private final int port;
    private final String label;

    /** Static capacity hint, used only by WEIGHTED_ROUND_ROBIN. */
    private final int weight;

    /** Requests dispatched but not yet answered. This is the "connections" count. */
    private final AtomicInteger inFlight = new AtomicInteger();

    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger declined = new AtomicInteger();

    // Guarded by this.
    private long latencySamples;
    private double totalLatencyMillis;
    private double ewmaLatencyMillis;

    /** Smooth weighted round robin's running counter; guarded by the LoadBalancer's lock. */
    private int currentWeight;

    private volatile boolean healthy = true;

    /**
     * @param nodeId the worker's node id, at least 1
     * @param port   the worker's TCP port, 1 to 65535 (used by the transport, never opened here)
     * @param label  a short display name, for example the capacity profile
     * @param weight static weight for WEIGHTED_ROUND_ROBIN, at least 1
     */
    public WorkerInfo(int nodeId, int port, String label, int weight) {
        if (nodeId < 1) {
            throw new IllegalArgumentException("nodeId must be >= 1, was " + nodeId);
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be 1-65535, was " + port);
        }
        Objects.requireNonNull(label, "label must not be null");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (weight < 1) {
            throw new IllegalArgumentException("weight must be >= 1, was " + weight);
        }
        this.nodeId = nodeId;
        this.port = port;
        this.label = label;
        this.weight = weight;
    }

    /**
     * A worker for a cluster node, labelled with its capacity profile and weighted by its
     * thread count: FAST 4, MEDIUM 2, SLOW 1 (the legacy 4 : 2 : 1).
     *
     * <p>These are <b>static</b> weights, fixed before the run. They describe the configured
     * thread count only, not the work multiplier (a SLOW node is about sixteen times weaker
     * than a FAST one, not four), and they never change with what the worker is actually
     * doing. That is the point of the comparison: weighted round robin beats plain round
     * robin, but only the live strategies (least connections, least response time) adapt to
     * real load, including a worker that has become slow for another reason, such as a
     * Multithreading batch running on the same node (R10).</p>
     */
    public static WorkerInfo of(int nodeId, int port, NodeCapacity capacity) {
        Objects.requireNonNull(capacity, "capacity must not be null");
        return new WorkerInfo(nodeId, port, capacity.name(), capacity.threads());
    }

    // ------------------------------------------------------------ bookkeeping (LoadBalancer only)

    void onDispatch() {
        inFlight.incrementAndGet();
    }

    /** The worker served a request; {@code latencyMillis} is that attempt's round trip. */
    void onComplete(double latencyMillis) {
        if (latencyMillis < 0 || !Double.isFinite(latencyMillis)) {
            throw new IllegalArgumentException("latencyMillis must be finite and >= 0, was " + latencyMillis);
        }
        synchronized (this) {
            latencySamples++;
            totalLatencyMillis += latencyMillis;
            ewmaLatencyMillis = latencySamples == 1
                    ? latencyMillis
                    : EWMA_ALPHA * latencyMillis + (1d - EWMA_ALPHA) * ewmaLatencyMillis;
        }
        completed.incrementAndGet();
        inFlight.decrementAndGet();
    }

    /** The worker could not be reached. */
    void onFailure() {
        failed.incrementAndGet();
        inFlight.decrementAndGet();
    }

    /** The worker answered but did not serve the request. */
    void onDeclined() {
        declined.incrementAndGet();
        inFlight.decrementAndGet();
    }

    /** The attempt ended without an outcome (the transport threw a RuntimeException). */
    void onAborted() {
        inFlight.decrementAndGet();
    }

    void markUnhealthy() {
        healthy = false;
    }

    void markHealthy() {
        healthy = true;
    }

    /** Clears the per-run counters. The in-flight count is live state and is kept. */
    void resetCounters() {
        completed.set(0);
        failed.set(0);
        declined.set(0);
        synchronized (this) {
            latencySamples = 0;
            totalLatencyMillis = 0d;
            ewmaLatencyMillis = 0d;
        }
        currentWeight = 0;
    }

    int currentWeight() {
        return currentWeight;
    }

    void setCurrentWeight(int currentWeight) {
        this.currentWeight = currentWeight;
    }

    // ------------------------------------------------------------ reads

    /**
     * Estimated time before this worker could finish one more request: the response-time
     * EWMA multiplied by (in-flight + 1). Before the first reply the EWMA counts as 1, so
     * unmeasured workers look cheap and get tried (as legacy).
     */
    public double estimatedCost() {
        double base;
        synchronized (this) {
            base = latencySamples == 0 ? 1d : ewmaLatencyMillis;
        }
        return base * (inFlight.get() + 1);
    }

    /** Mean round trip of the requests this worker served; empty before the first. */
    public synchronized OptionalDouble averageLatencyMillis() {
        return latencySamples == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of(totalLatencyMillis / latencySamples);
    }

    /** Exponentially weighted moving average of round trips; empty before the first. */
    public synchronized OptionalDouble ewmaLatencyMillis() {
        return latencySamples == 0 ? OptionalDouble.empty() : OptionalDouble.of(ewmaLatencyMillis);
    }

    public int nodeId() {
        return nodeId;
    }

    public int port() {
        return port;
    }

    public String label() {
        return label;
    }

    public int weight() {
        return weight;
    }

    public int inFlight() {
        return inFlight.get();
    }

    public int completed() {
        return completed.get();
    }

    public int failed() {
        return failed.get();
    }

    public int declined() {
        return declined.get();
    }

    public boolean isHealthy() {
        return healthy;
    }
}
