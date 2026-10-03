package com.udcf.loadbalancer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The load balancer's live view of one worker node.
 *
 * <p>Everything here is measured by the balancer itself as requests go out and replies
 * come back. Nothing is reported by the worker and nothing is assumed, which is what
 * lets the balancer react to a worker that has become slow rather than one that was
 * merely configured to be slow.</p>
 */
public class WorkerInfo {

    private final int nodeId;
    private final int port;
    private final String label;

    /** Static capacity hint, used only by WEIGHTED_ROUND_ROBIN. */
    private final int weight;

    /** Requests dispatched but not yet answered. This is the "connections" count. */
    private final AtomicInteger inFlight = new AtomicInteger();

    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicLong totalLatencyMicros = new AtomicLong();

    /** Exponentially weighted moving average of response time, in milliseconds. */
    private volatile double ewmaLatencyMillis = 0d;

    /** Smooth weighted round robin needs one mutable counter per worker. */
    private int currentWeight = 0;

    private volatile boolean healthy = true;

    public WorkerInfo(int nodeId, int port, String label, int weight) {
        this.nodeId = nodeId;
        this.port = port;
        this.label = label;
        this.weight = weight;
    }

    public void onDispatch()  { inFlight.incrementAndGet(); }
    public void onComplete(double latencyMillis) {
        inFlight.decrementAndGet();
        completed.incrementAndGet();
        totalLatencyMicros.addAndGet((long) (latencyMillis * 1000));
        // Alpha of 0.3 keeps the estimate responsive without letting one slow
        // request dominate it.
        ewmaLatencyMillis = (ewmaLatencyMillis == 0d)
                ? latencyMillis
                : 0.3d * latencyMillis + 0.7d * ewmaLatencyMillis;
    }
    public void onFailure() {
        inFlight.decrementAndGet();
        failed.incrementAndGet();
    }

    /** Estimated time before this worker could finish one more request. */
    public double estimatedCost() {
        double base = ewmaLatencyMillis == 0d ? 1d : ewmaLatencyMillis;
        return base * (inFlight.get() + 1);
    }

    public double averageLatencyMillis() {
        int done = completed.get();
        return done == 0 ? 0d : round(totalLatencyMicros.get() / 1000d / done);
    }

    public void resetCounters() {
        completed.set(0);
        failed.set(0);
        totalLatencyMicros.set(0);
        inFlight.set(0);
        ewmaLatencyMillis = 0d;
        currentWeight = 0;
    }

    private static double round(double v) { return Math.round(v * 100d) / 100d; }

    public int nodeId()            { return nodeId; }
    public int port()              { return port; }
    public String label()          { return label; }
    public int weight()            { return weight; }
    public int inFlight()          { return inFlight.get(); }
    public int completed()         { return completed.get(); }
    public int failed()            { return failed.get(); }
    public double ewmaLatency()    { return round(ewmaLatencyMillis); }
    public boolean isHealthy()     { return healthy; }
    public void markUnhealthy()    { healthy = false; }
    public void markHealthy()      { healthy = true; }
    int currentWeight()            { return currentWeight; }
    void setCurrentWeight(int w)   { currentWeight = w; }
}
