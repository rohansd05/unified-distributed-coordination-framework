package com.udcf.modules.loadbalancing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The gateway: it chooses a worker for every request, sends it through a
 * {@link WorkerTransport}, and reroutes when a worker fails. Pure algorithm: no sockets, no
 * threads, no Spring. The transport is the network (E6b); the caller provides concurrency.
 *
 * <p><b>Thread safety.</b> {@link #dispatch} may be called from many threads at once (with
 * one request at a time LEAST_CONNECTIONS would be indistinguishable from ROUND_ROBIN).
 * Choosing a worker and incrementing its in-flight count happen together under this
 * object's lock, so two threads can never both see the same worker as least busy before
 * either has counted its request. Decrements happen outside the lock when a reply arrives;
 * a pick that just misses one sees the worker as busier than it is for an instant, which
 * is conservative and never sends two requests on one stale reading.</p>
 *
 * <p><b>Reroute (circuit breaker).</b> Each request tries at most one attempt per worker,
 * never the same worker twice. An {@link IOException} marks the worker unhealthy for the
 * rest of the run; a {@link WorkerDeclinedException} reroutes but leaves it healthy. A
 * worker failure therefore becomes a slower request rather than a lost one, and the
 * request's latency includes the failed attempt.</p>
 *
 * <p>Ported from legacy-demos/exp06-load-balancing ({@code LoadBalancer.select},
 * {@code smoothWeighted}, {@code dispatch}); the pick sequences are the legacy ones, checked
 * in LoadBalancerTest. Deliberate differences:</p>
 * <ul>
 *   <li>Selection and the in-flight increment are one atomic step (legacy incremented after
 *       leaving {@code select}, so concurrent picks could race).</li>
 *   <li>Workers are kept in node-id order whatever order they are given in.</li>
 *   <li>LEAST_RESPONSE_TIME breaks ties to the lower node id explicitly (legacy kept the
 *       first in list order, which is the same on an id-ordered list).</li>
 *   <li>A request never retries a worker it already tried; a declined attempt reroutes
 *       without tripping the circuit breaker.</li>
 *   <li>Latency is end to end, from the first attempt to the final answer (legacy timed the
 *       last attempt only); the worker's own average gets only the attempt it served.</li>
 *   <li>A RuntimeException from the transport releases the in-flight slot, then propagates.</li>
 *   <li>The batch runner, concurrent clients and the Lamport clock are not here: E6b.</li>
 * </ul>
 */
public class LoadBalancer {

    private final List<WorkerInfo> workers;
    private final WorkerTransport transport;
    private final LongSupplier nanoClock;

    // Guarded by this.
    private int roundRobinCursor;

    public LoadBalancer(List<WorkerInfo> workers, WorkerTransport transport) {
        this(workers, transport, System::nanoTime);
    }

    /**
     * @param workers   at least one worker, distinct node ids (NullPointerException for a null
     *                  list or element, IllegalArgumentException for an empty list or duplicates)
     * @param transport sends a request to a worker
     * @param nanoClock monotonic nanosecond time source (System::nanoTime outside tests)
     */
    public LoadBalancer(List<WorkerInfo> workers, WorkerTransport transport, LongSupplier nanoClock) {
        Objects.requireNonNull(workers, "workers must not be null");
        Objects.requireNonNull(transport, "transport must not be null");
        Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        List<WorkerInfo> sorted = new ArrayList<>(workers.size());
        Set<Integer> ids = new HashSet<>();
        for (WorkerInfo w : workers) {
            Objects.requireNonNull(w, "workers must not contain null");
            if (!ids.add(w.nodeId())) {
                throw new IllegalArgumentException("duplicate worker nodeId " + w.nodeId());
            }
            sorted.add(w);
        }
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("workers must not be empty");
        }
        sorted.sort(Comparator.comparingInt(WorkerInfo::nodeId));
        this.workers = Collections.unmodifiableList(sorted);
        this.transport = transport;
        this.nanoClock = nanoClock;
    }

    // ------------------------------------------------------------ the algorithms

    /**
     * Chooses the next worker exactly as the legacy {@code select} did, without reserving it.
     * Package-private: production code dispatches through {@link #dispatch}, which chooses
     * and reserves atomically. Returns null only if every worker is unhealthy.
     */
    synchronized WorkerInfo select(Strategy strategy) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        return choose(strategy, Set.of());
    }

    /** Chooses and counts the request in flight in one step. */
    private synchronized WorkerInfo reserve(Strategy strategy, Set<Integer> excluded) {
        WorkerInfo chosen = choose(strategy, excluded);
        if (chosen != null) {
            chosen.onDispatch();
        }
        return chosen;
    }

    /** Caller holds this object's lock. */
    private WorkerInfo choose(Strategy strategy, Set<Integer> excluded) {
        List<WorkerInfo> candidates = new ArrayList<>(workers.size());
        for (WorkerInfo w : workers) {
            if (w.isHealthy() && !excluded.contains(w.nodeId())) {
                candidates.add(w);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        return switch (strategy) {
            case ROUND_ROBIN -> candidates.get(Math.floorMod(roundRobinCursor++, candidates.size()));

            case WEIGHTED_ROUND_ROBIN -> smoothWeighted(candidates);

            // Fewest requests dispatched but not yet answered. Ties go to the lower id
            // so the choice is deterministic and reproducible between runs.
            case LEAST_CONNECTIONS -> {
                WorkerInfo best = candidates.get(0);
                for (WorkerInfo w : candidates) {
                    if (w.inFlight() < best.inFlight()
                            || (w.inFlight() == best.inFlight() && w.nodeId() < best.nodeId())) {
                        best = w;
                    }
                }
                yield best;
            }

            // Lowest estimated time to finish one more request: recent average response
            // time multiplied by the work already outstanding at that worker. Ties go to
            // the lower id.
            case LEAST_RESPONSE_TIME -> {
                WorkerInfo best = candidates.get(0);
                double bestCost = best.estimatedCost();
                for (WorkerInfo w : candidates) {
                    double cost = w.estimatedCost();
                    if (cost < bestCost || (cost == bestCost && w.nodeId() < best.nodeId())) {
                        best = w;
                        bestCost = cost;
                    }
                }
                yield best;
            }
        };
    }

    /**
     * Smooth weighted round robin, the algorithm nginx uses.
     *
     * <p>Each worker accumulates its weight; the highest total is chosen and then has the
     * sum of all weights subtracted. Over time each worker is picked in proportion to its
     * weight, but the picks are spread out instead of arriving in one burst — a plain
     * weighted scheme would send four requests to the fast worker back to back. Ties go to
     * the first (lowest id) worker.</p>
     */
    private static WorkerInfo smoothWeighted(List<WorkerInfo> candidates) {
        int totalWeight = 0;
        WorkerInfo best = null;
        for (WorkerInfo w : candidates) {
            w.setCurrentWeight(w.currentWeight() + w.weight());
            totalWeight += w.weight();
            if (best == null || w.currentWeight() > best.currentWeight()) {
                best = w;
            }
        }
        if (best != null) {
            best.setCurrentWeight(best.currentWeight() - totalWeight);
        }
        return best;
    }

    // ------------------------------------------------------------ dispatch

    /**
     * Sends one request, rerouting to a different worker if the chosen one fails. Gives up
     * after one attempt per worker, or at once if no untried worker is healthy.
     *
     * @param requestId at least 1
     * @param workUnits units of work the request carries, at least 1
     * @param strategy  the algorithm that chooses each worker
     * @throws RuntimeException whatever the transport threw, after the worker's in-flight
     *                          slot has been released
     */
    public DispatchResult dispatch(int requestId, int workUnits, Strategy strategy) {
        if (requestId < 1) {
            throw new IllegalArgumentException("requestId must be >= 1, was " + requestId);
        }
        if (workUnits < 1) {
            throw new IllegalArgumentException("workUnits must be >= 1, was " + workUnits);
        }
        Objects.requireNonNull(strategy, "strategy must not be null");

        Set<Integer> tried = new HashSet<>();
        long firstAttempt = nanoClock.getAsLong();
        int attempts = 0;

        while (attempts < workers.size()) {
            WorkerInfo worker = reserve(strategy, tried);
            if (worker == null) {
                break;
            }
            attempts++;
            tried.add(worker.nodeId());

            long start = nanoClock.getAsLong();
            boolean settled = false;
            try {
                transport.send(worker, requestId, workUnits);
                long end = nanoClock.getAsLong();
                worker.onComplete(millis(end - start));
                settled = true;
                return new DispatchResult(requestId, worker.nodeId(), millis(end - firstAttempt), true, attempts);
            } catch (IOException e) {
                worker.onFailure();
                settled = true;
                worker.markUnhealthy();   // circuit breaker: stop choosing it this run
            } catch (WorkerDeclinedException e) {
                worker.onDeclined();      // alive, so it stays healthy; try someone else
                settled = true;
            } finally {
                if (!settled) {
                    worker.onAborted();
                }
            }
        }
        return new DispatchResult(requestId, 0, millis(nanoClock.getAsLong() - firstAttempt), false, attempts);
    }

    /**
     * Starts a clean run: every worker healthy again, counters, round robin cursor and smooth
     * weights cleared. Safe while requests are still in flight: their in-flight counts are
     * kept, and when they finish they are counted in the new run.
     */
    public synchronized void resetForRun() {
        roundRobinCursor = 0;
        for (WorkerInfo w : workers) {
            w.resetCounters();
            w.markHealthy();
        }
    }

    /** The workers in node-id order (unmodifiable). */
    public List<WorkerInfo> workers() {
        return workers;
    }

    private static double millis(long nanos) {
        return Math.max(0L, nanos) / 1_000_000d;
    }
}
