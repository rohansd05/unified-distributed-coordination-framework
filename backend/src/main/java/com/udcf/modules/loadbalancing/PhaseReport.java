package com.udcf.modules.loadbalancing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Aggregated measurements for one algorithm over one batch of requests.
 *
 * <p>Every figure is computed from the {@link DispatchResult} list, which in turn is built
 * from real round trips, and the makespan is measured by the caller (E6b). Nothing here is
 * estimated.</p>
 *
 * <p>Ported from legacy-demos/exp06-load-balancing. Differences: requests no worker served
 * are no longer counted under a "node 0"; every node in the phase appears in
 * {@link #requestsPerNode()}, with 0 if it served nothing, so {@link #loadSpread()} covers
 * all of them; latency figures cover served requests only and are empty when none was
 * served (legacy returned 0); nothing is rounded here.</p>
 */
public class PhaseReport {

    private final Strategy strategy;
    private final List<Integer> nodeIds;
    private final List<DispatchResult> results;
    private final double makespanMillis;

    /**
     * @param strategy       the algorithm used
     * @param nodeIds        every worker in the phase, at least one, distinct
     * @param results        one result per request; a served request's node must be in nodeIds
     * @param makespanMillis time from the first request sent to the last answer, finite and >= 0
     */
    public PhaseReport(Strategy strategy, List<Integer> nodeIds, List<DispatchResult> results,
                       double makespanMillis) {
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        Objects.requireNonNull(nodeIds, "nodeIds must not be null");
        Objects.requireNonNull(results, "results must not be null");
        List<Integer> sortedIds = new ArrayList<>(List.copyOf(nodeIds));
        if (sortedIds.isEmpty()) {
            throw new IllegalArgumentException("nodeIds must not be empty");
        }
        if (new HashSet<>(sortedIds).size() != sortedIds.size()) {
            throw new IllegalArgumentException("nodeIds must be distinct, was " + nodeIds);
        }
        Collections.sort(sortedIds);
        List<DispatchResult> copied = List.copyOf(results);
        Set<Integer> known = new HashSet<>(sortedIds);
        for (DispatchResult r : copied) {
            if (r.succeeded() && !known.contains(r.nodeId())) {
                throw new IllegalArgumentException("result " + r.requestId()
                        + " was served by node " + r.nodeId() + ", which is not in nodeIds");
            }
        }
        if (makespanMillis < 0 || !Double.isFinite(makespanMillis)) {
            throw new IllegalArgumentException("makespanMillis must be finite and >= 0, was " + makespanMillis);
        }
        this.nodeIds = List.copyOf(sortedIds);
        this.results = copied;
        this.makespanMillis = makespanMillis;
    }

    /** How many requests each worker served, in node-id order, 0 for a worker that served none. */
    public Map<Integer, Integer> requestsPerNode() {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int id : nodeIds) {
            counts.put(id, 0);
        }
        for (DispatchResult r : results) {
            if (r.succeeded()) {
                counts.merge(r.nodeId(), 1, Integer::sum);
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    /** Mean latency of the requests each worker served, in node-id order; empty for none. */
    public Map<Integer, OptionalDouble> averageLatencyByNode() {
        Map<Integer, OptionalDouble> averages = new LinkedHashMap<>();
        for (int id : nodeIds) {
            averages.put(id, results.stream()
                    .filter(r -> r.succeeded() && r.nodeId() == id)
                    .mapToDouble(DispatchResult::latencyMillis)
                    .average());
        }
        return Collections.unmodifiableMap(averages);
    }

    public OptionalDouble averageLatency() {
        return servedLatencies().stream().mapToDouble(Double::doubleValue).average();
    }

    /** Nearest-rank 95th percentile — the slowest requests are what users notice. */
    public OptionalDouble p95Latency() {
        List<Double> v = servedLatencies();
        if (v.isEmpty()) {
            return OptionalDouble.empty();
        }
        Collections.sort(v);
        int rank = (int) Math.ceil(0.95 * v.size());
        return OptionalDouble.of(v.get(Math.min(v.size() - 1, Math.max(0, rank - 1))));
    }

    public OptionalDouble maxLatency() {
        return servedLatencies().stream().mapToDouble(Double::doubleValue).max();
    }

    /**
     * Spread between the busiest and least busy worker, by requests served.
     *
     * <p>A low spread is not automatically good. Round robin achieves a spread of zero
     * by giving the slow worker just as much work as the fast one, which is exactly what
     * makes it finish last.</p>
     */
    public int loadSpread() {
        Map<Integer, Integer> counts = requestsPerNode();
        return Collections.max(counts.values()) - Collections.min(counts.values());
    }

    /** Requests no worker served. */
    public long failures() {
        return results.stream().filter(r -> !r.succeeded()).count();
    }

    /** Requests that tried more than one worker. */
    public long reroutes() {
        return results.stream().filter(DispatchResult::rerouted).count();
    }

    public long served() {
        return results.stream().filter(DispatchResult::succeeded).count();
    }

    private List<Double> servedLatencies() {
        List<Double> v = new ArrayList<>();
        for (DispatchResult r : results) {
            if (r.succeeded()) {
                v.add(r.latencyMillis());
            }
        }
        return v;
    }

    public Strategy strategy() {
        return strategy;
    }

    /** The phase's workers in node-id order (unmodifiable). */
    public List<Integer> nodeIds() {
        return nodeIds;
    }

    /** Every result, unmodifiable. */
    public List<DispatchResult> results() {
        return results;
    }

    public double makespanMillis() {
        return makespanMillis;
    }

    public int total() {
        return results.size();
    }
}
