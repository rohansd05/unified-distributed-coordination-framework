package com.udcf.modules.loadbalancing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Fires one batch of requests through a {@link LoadBalancer} from several concurrent
 * clients and measures it: the legacy {@code LoadBalancer.runBatch}.
 *
 * <p>Concurrency matters: with one request at a time the in-flight count never exceeds one
 * and LEAST_CONNECTIONS would be indistinguishable from ROUND_ROBIN. {@code concurrency}
 * client loops (legacy: a fixed pool of 12) each take the next request id until all are
 * sent, so at most {@code concurrency} requests are in flight at once. The loops run on
 * virtual threads named {@value #CLIENT_THREAD_PREFIX}{@code <i>}: they spend their time
 * blocked on sockets, and nothing in the dispatch path holds a monitor across a socket call,
 * so they never pin a carrier thread.</p>
 *
 * <p>The makespan runs from just before the first request is handed to a client to the
 * moment the last client has its answer. Every figure in the returned {@link PhaseReport} is
 * measured.</p>
 *
 * <p>If the calling thread is interrupted, the client loops are interrupted and the run still
 * waits for them (each in-flight attempt then ends as the transport reports it), so the report
 * is always complete. Covered by BatchRunnerTest.</p>
 */
public class BatchRunner {

    public static final String CLIENT_THREAD_PREFIX = "udcf-lb-client-";

    private final LongSupplier nanoClock;

    public BatchRunner() {
        this(System::nanoTime);
    }

    /** @param nanoClock monotonic nanosecond time source for the makespan */
    public BatchRunner(LongSupplier nanoClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
    }

    /**
     * Resets the balancer for a clean run ({@link LoadBalancer#resetForRun()}), then sends
     * requests 1..{@code requestCount}, each with {@code workUnits}, from {@code concurrency}
     * clients, and waits for every answer.
     *
     * @return the measured phase, results in request-id order
     * @throws RuntimeException the first exception a client hit (a transport bug), after every
     *                          client has stopped; the other clients stop taking new requests
     */
    public PhaseReport run(LoadBalancer balancer, Strategy strategy, int requestCount, int workUnits, int concurrency) {
        Objects.requireNonNull(balancer, "balancer must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        requireAtLeastOne(requestCount, "requestCount");
        requireAtLeastOne(workUnits, "workUnits");
        requireAtLeastOne(concurrency, "concurrency");

        balancer.resetForRun();
        DispatchResult[] results = new DispatchResult[requestCount];
        AtomicInteger nextId = new AtomicInteger(1);
        AtomicBoolean aborted = new AtomicBoolean();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        int clients = Math.min(concurrency, requestCount);
        List<Future<?>> futures = new ArrayList<>(clients);

        long start = nanoClock.getAsLong();
        try (ExecutorService pool = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(CLIENT_THREAD_PREFIX, 1).factory())) {
            for (int c = 0; c < clients; c++) {
                futures.add(pool.submit(() -> {
                    try {
                        int id;
                        while (!aborted.get() && (id = nextId.getAndIncrement()) <= requestCount) {
                            results[id - 1] = balancer.dispatch(id, workUnits, strategy);
                        }
                    } catch (RuntimeException | Error e) {
                        aborted.set(true);
                        firstError.compareAndSet(null, e);
                        throw e;
                    }
                }));
            }
        }   // close() waits for every client
        long end = nanoClock.getAsLong();

        rethrowFirst(firstError.get(), futures);
        List<Integer> nodeIds = balancer.workers().stream().map(WorkerInfo::nodeId).toList();
        return new PhaseReport(strategy, nodeIds, Arrays.asList(results), Math.max(0L, end - start) / 1_000_000d);
    }

    private static void rethrowFirst(Throwable first, List<Future<?>> futures) {
        if (first == null) {
            for (Future<?> f : futures) {      // all done: get() never blocks here
                try {
                    f.get();
                } catch (ExecutionException e) {
                    first = e.getCause();
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (first instanceof RuntimeException r) {
            throw r;
        }
        if (first instanceof Error e) {
            throw e;
        }
        if (first != null) {
            throw new IllegalStateException("A load balancer client failed", first);
        }
    }

    private static void requireAtLeastOne(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be >= 1, was " + value);
        }
    }
}
