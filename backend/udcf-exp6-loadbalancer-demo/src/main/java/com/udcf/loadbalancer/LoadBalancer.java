package com.udcf.loadbalancer;

import com.udcf.sync.LamportClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The gateway: it chooses a worker for every request and measures what happened.
 *
 * <p>This is the whole experiment in one class. {@link #select(Strategy)} contains the
 * four algorithms; everything else exists to drive them with real concurrent traffic and
 * record the result.</p>
 *
 * <p>Requests are dispatched from a thread pool, so many are in flight at once. That
 * matters: with one request at a time the in-flight count is never greater than one and
 * LEAST_CONNECTIONS would be indistinguishable from ROUND_ROBIN.</p>
 */
public class LoadBalancer {

    /** Timeout for connecting to and hearing back from a worker. */
    private static final int REQUEST_TIMEOUT_MS = 8000;

    private final List<WorkerInfo> workers;
    private final LamportClock clock = new LamportClock();
    private final AtomicInteger roundRobinCursor = new AtomicInteger();
    private final ExecutorService clientPool;

    public LoadBalancer(List<WorkerInfo> workers, int concurrentClients) {
        this.workers = workers;
        this.clientPool = Executors.newFixedThreadPool(concurrentClients, r -> {
            Thread t = new Thread(r, "lb-client");
            t.setDaemon(true);
            return t;
        });
    }

    // ------------------------------------------------------------ the algorithms

    /** Chooses the next worker. Returns null only if every worker is unhealthy. */
    public synchronized WorkerInfo select(Strategy strategy) {
        List<WorkerInfo> healthy = new ArrayList<>();
        for (WorkerInfo w : workers) {
            if (w.isHealthy()) { healthy.add(w); }
        }
        if (healthy.isEmpty()) { return null; }

        return switch (strategy) {
            case ROUND_ROBIN -> healthy.get(
                    Math.floorMod(roundRobinCursor.getAndIncrement(), healthy.size()));

            case WEIGHTED_ROUND_ROBIN -> smoothWeighted(healthy);

            // Fewest requests dispatched but not yet answered. Ties go to the lower id
            // so the choice is deterministic and reproducible between runs.
            case LEAST_CONNECTIONS -> {
                WorkerInfo best = healthy.get(0);
                for (WorkerInfo w : healthy) {
                    if (w.inFlight() < best.inFlight()
                            || (w.inFlight() == best.inFlight() && w.nodeId() < best.nodeId())) {
                        best = w;
                    }
                }
                yield best;
            }

            // Lowest estimated time to finish one more request: recent average response
            // time multiplied by the work already queued at that worker.
            case LEAST_RESPONSE_TIME -> {
                WorkerInfo best = healthy.get(0);
                for (WorkerInfo w : healthy) {
                    if (w.estimatedCost() < best.estimatedCost()) { best = w; }
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
     * weighted scheme would send four requests to the fast worker back to back.</p>
     */
    private WorkerInfo smoothWeighted(List<WorkerInfo> healthy) {
        int totalWeight = 0;
        WorkerInfo best = null;
        for (WorkerInfo w : healthy) {
            w.setCurrentWeight(w.currentWeight() + w.weight());
            totalWeight += w.weight();
            if (best == null || w.currentWeight() > best.currentWeight()) { best = w; }
        }
        if (best != null) { best.setCurrentWeight(best.currentWeight() - totalWeight); }
        return best;
    }

    // ------------------------------------------------------------ dispatch

    /**
     * Sends one request, retrying on a different worker if the chosen one is unreachable.
     *
     * <p>The retry is what turns a worker failure into a slower request rather than a
     * failed one, which is how a real load balancer behaves.</p>
     */
    public DispatchResult dispatch(int requestId, int workUnits, Strategy strategy) {
        boolean rerouted = false;

        for (int attempt = 0; attempt < workers.size(); attempt++) {
            WorkerInfo worker = select(strategy);
            if (worker == null) {
                return new DispatchResult(requestId, 0, 0, false, rerouted);
            }

            worker.onDispatch();
            long start = System.nanoTime();
            try {
                send(worker, "WORK", String.valueOf(workUnits));
                double latency = (System.nanoTime() - start) / 1_000_000d;
                worker.onComplete(latency);
                return new DispatchResult(requestId, worker.nodeId(), round(latency), true, rerouted);
            } catch (IOException e) {
                worker.onFailure();
                worker.markUnhealthy();   // circuit breaker: stop choosing it
                rerouted = true;
            }
        }
        return new DispatchResult(requestId, 0, 0, false, rerouted);
    }

    /** Fires a whole batch concurrently and waits for every reply. */
    public PhaseReport runBatch(Strategy strategy, int requestCount, int workUnits) {
        for (WorkerInfo w : workers) { w.resetCounters(); }
        roundRobinCursor.set(0);

        List<Callable<DispatchResult>> tasks = new ArrayList<>();
        for (int i = 1; i <= requestCount; i++) {
            final int id = i;
            tasks.add(() -> dispatch(id, workUnits, strategy));
        }

        long start = System.nanoTime();
        List<DispatchResult> results = new ArrayList<>();
        try {
            for (Future<DispatchResult> f : clientPool.invokeAll(tasks)) {
                results.add(f.get());
            }
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
        double makespan = (System.nanoTime() - start) / 1_000_000d;

        return new PhaseReport(strategy, results, makespan);
    }

    /** Asks a worker directly how busy it is. Used to show live state, not to balance. */
    public String health(WorkerInfo worker) {
        try {
            return send(worker, "HEALTH", "0");
        } catch (IOException e) {
            return "unreachable";
        }
    }

    private String send(WorkerInfo worker, String type, String payload) throws IOException {
        long stamped = clock.tick();                       // Lamport Rule 2
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", worker.port()), REQUEST_TIMEOUT_MS);
            socket.setSoTimeout(REQUEST_TIMEOUT_MS);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.println(type + "|0|" + stamped + "|" + payload);
            String response = in.readLine();
            if (response == null) {
                throw new IOException("no reply from node " + worker.nodeId());
            }
            String[] parts = response.split("\\|", 4);
            clock.update(Long.parseLong(parts[2]));        // Lamport Rule 3
            return parts.length > 3 ? parts[3] : "";
        }
    }

    public void restoreAllHealthy() {
        for (WorkerInfo w : workers) { w.markHealthy(); }
    }

    public List<WorkerInfo> workers() { return Collections.unmodifiableList(workers); }
    public long clockValue()          { return clock.current(); }

    private static double round(double v) { return Math.round(v * 100d) / 100d; }

    public void shutdown() { clientPool.shutdownNow(); }
}
