package com.udcf.loadbalancer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregated measurements for one algorithm over one batch of requests.
 *
 * <p>Every figure is computed from the {@link DispatchResult} list, which in turn is
 * built from real round trips. Nothing here is estimated.</p>
 */
public class PhaseReport {

    private final Strategy strategy;
    private final List<DispatchResult> results;
    private final double makespanMillis;

    public PhaseReport(Strategy strategy, List<DispatchResult> results, double makespanMillis) {
        this.strategy = strategy;
        this.results = results;
        this.makespanMillis = makespanMillis;
    }

    /** How many requests each worker served. */
    public Map<Integer, Integer> requestsPerNode() {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (DispatchResult r : results) {
            counts.merge(r.nodeId(), 1, Integer::sum);
        }
        return counts;
    }

    public double averageLatency() {
        double total = 0;
        int n = 0;
        for (DispatchResult r : results) {
            if (r.succeeded()) { total += r.latencyMillis(); n++; }
        }
        return n == 0 ? 0 : round(total / n);
    }

    /** Nearest-rank 95th percentile — the slowest requests are what users notice. */
    public double p95Latency() {
        List<Double> v = new ArrayList<>();
        for (DispatchResult r : results) {
            if (r.succeeded()) { v.add(r.latencyMillis()); }
        }
        if (v.isEmpty()) { return 0; }
        Collections.sort(v);
        int rank = (int) Math.ceil(0.95 * v.size());
        return round(v.get(Math.min(v.size() - 1, Math.max(0, rank - 1))));
    }

    public double maxLatency() {
        double max = 0;
        for (DispatchResult r : results) { max = Math.max(max, r.latencyMillis()); }
        return round(max);
    }

    /**
     * Spread between the busiest and least busy worker.
     *
     * <p>A low spread is not automatically good. Round robin achieves a spread of zero
     * by giving the slow worker just as much work as the fast one, which is exactly what
     * makes it finish last.</p>
     */
    public int loadSpread() {
        Map<Integer, Integer> counts = requestsPerNode();
        if (counts.isEmpty()) { return 0; }
        int max = Collections.max(counts.values());
        int min = Collections.min(counts.values());
        return max - min;
    }

    public long failures() {
        return results.stream().filter(r -> !r.succeeded()).count();
    }

    public long reroutes() {
        return results.stream().filter(DispatchResult::rerouted).count();
    }

    private static double round(double v) { return Math.round(v * 100d) / 100d; }

    public Strategy strategy()      { return strategy; }
    public double makespanMillis()  { return round(makespanMillis); }
    public int total()              { return results.size(); }
}
