package com.udcf.modules.loadbalancing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The batch runner with in-memory transports: no sockets, no sleeps. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class BatchRunnerTest {

    private static final long GUARD_SECONDS = 10;

    /** A nanosecond clock that moves only when a transport advances it. */
    private static final class ManualClock implements LongSupplier {
        private final AtomicLong nanos = new AtomicLong(5_000_000_000L);

        void advanceMillis(long millis) {
            nanos.addAndGet(millis * 1_000_000L);
        }

        @Override
        public long getAsLong() {
            return nanos.get();
        }
    }

    private static List<WorkerInfo> workers(int count) {
        List<WorkerInfo> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(new WorkerInfo(i, 7200 + i, "W" + i, 1));
        }
        return list;
    }

    @Test
    @DisplayName("dispatches every request id exactly once and returns the results in id order")
    void everyIdOnce() {
        Set<Integer> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicates = new AtomicInteger();
        LoadBalancer lb = new LoadBalancer(workers(3), (w, id, units) -> {
            if (!seen.add(id)) {
                duplicates.incrementAndGet();
            }
        });

        PhaseReport report = new BatchRunner().run(lb, Strategy.ROUND_ROBIN, 50, 7, 5);

        assertThat(report.results()).extracting(DispatchResult::requestId)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 50).boxed().toList());
        assertThat(report.results()).allMatch(DispatchResult::succeeded);
        assertThat(seen).hasSize(50);
        assertThat(duplicates).hasValue(0);
        assertThat(report.nodeIds()).containsExactly(1, 2, 3);
        assertThat(report.strategy()).isEqualTo(Strategy.ROUND_ROBIN);
    }

    @Test
    @DisplayName("exactly `concurrency` requests are in flight at once, never more")
    void exactConcurrency() {
        int concurrency = 4;
        CyclicBarrier allFour = new CyclicBarrier(concurrency);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger highWater = new AtomicInteger();
        LoadBalancer lb = new LoadBalancer(workers(2), (w, id, units) -> {
            highWater.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                // Only passes when 4 requests are in flight together.
                allFour.await(GUARD_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                throw new IllegalStateException(e);
            } finally {
                inFlight.decrementAndGet();
            }
        });

        PhaseReport report = new BatchRunner().run(lb, Strategy.LEAST_CONNECTIONS, 12, 1, concurrency);

        assertThat(report.served()).isEqualTo(12);
        assertThat(highWater).hasValue(concurrency);
    }

    @Test
    @DisplayName("more clients than requests: one client per request, all served")
    void concurrencyAboveRequestCount() {
        Set<String> threads = ConcurrentHashMap.newKeySet();
        LoadBalancer lb = new LoadBalancer(workers(2),
                (w, id, units) -> threads.add(Thread.currentThread().getName()));

        PhaseReport report = new BatchRunner().run(lb, Strategy.ROUND_ROBIN, 3, 1, 10);

        assertThat(report.served()).isEqualTo(3);
        assertThat(threads).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("clients are named virtual threads")
    void virtualNamedThreads() {
        List<Thread> seen = new CopyOnWriteArrayList<>();
        LoadBalancer lb = new LoadBalancer(workers(2), (w, id, units) -> seen.add(Thread.currentThread()));

        new BatchRunner().run(lb, Strategy.ROUND_ROBIN, 8, 1, 4);

        assertThat(seen).hasSize(8).allMatch(Thread::isVirtual)
                .allMatch(t -> t.getName().startsWith(BatchRunner.CLIENT_THREAD_PREFIX));
    }

    @Test
    @DisplayName("the makespan is measured from the first request to the last answer")
    void makespanFromClock() {
        ManualClock clock = new ManualClock();
        LoadBalancer lb = new LoadBalancer(workers(2), (w, id, units) -> clock.advanceMillis(10), clock);

        PhaseReport report = new BatchRunner(clock).run(lb, Strategy.ROUND_ROBIN, 5, 1, 1);

        assertThat(report.makespanMillis()).isEqualTo(50d);
        assertThat(report.results()).allMatch(r -> r.latencyMillis() == 10d);
    }

    @Test
    @DisplayName("each run starts clean: a worker the breaker removed last run is used again")
    void resetsBetweenRuns() {
        AtomicBoolean nodeOneDown = new AtomicBoolean(true);
        LoadBalancer lb = new LoadBalancer(workers(2), (w, id, units) -> {
            if (w.nodeId() == 1 && nodeOneDown.get()) {
                throw new ConnectException("refused");
            }
        });
        BatchRunner runner = new BatchRunner();

        PhaseReport first = runner.run(lb, Strategy.ROUND_ROBIN, 4, 1, 1);
        nodeOneDown.set(false);
        PhaseReport second = runner.run(lb, Strategy.ROUND_ROBIN, 4, 1, 1);

        assertThat(first.requestsPerNode()).containsEntry(1, 0).containsEntry(2, 4);
        assertThat(first.failures()).isZero();
        assertThat(second.requestsPerNode()).containsEntry(1, 2).containsEntry(2, 2);
        assertThat(lb.workers()).allMatch(WorkerInfo::isHealthy);
    }

    @Test
    @DisplayName("a transport RuntimeException is rethrown after every client stops, with nothing left in flight")
    void transportBugRethrown() {
        IllegalStateException bug = new IllegalStateException("transport bug");
        AtomicInteger inTransport = new AtomicInteger();
        LoadBalancer lb = new LoadBalancer(workers(3), (w, id, units) -> {
            inTransport.incrementAndGet();
            try {
                if (id == 5) {
                    throw bug;
                }
            } finally {
                inTransport.decrementAndGet();
            }
        });

        assertThatThrownBy(() -> new BatchRunner().run(lb, Strategy.ROUND_ROBIN, 200, 1, 4)).isSameAs(bug);

        assertThat(inTransport).hasValue(0);
        assertThat(lb.workers()).allMatch(w -> w.inFlight() == 0);
    }

    @Test
    @DisplayName("the observer sees every result exactly once, on the client thread that dispatched it")
    void observerSeesEveryResultOnce() {
        Set<Integer> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger calls = new AtomicInteger();
        List<Boolean> onClientThread = new CopyOnWriteArrayList<>();
        LoadBalancer lb = new LoadBalancer(workers(3), (w, id, units) -> { });

        PhaseReport report = new BatchRunner().run(lb, Strategy.LEAST_CONNECTIONS, 40, 1, 6, result -> {
            calls.incrementAndGet();
            seen.add(result.requestId());
            onClientThread.add(Thread.currentThread().getName().startsWith(BatchRunner.CLIENT_THREAD_PREFIX));
        });

        assertThat(report.served()).isEqualTo(40);
        assertThat(calls).hasValue(40);
        assertThat(seen).hasSize(40);
        assertThat(onClientThread).hasSize(40).containsOnly(true);
    }

    @Test
    @DisplayName("an exception from the observer ends the run like a transport bug and is rethrown")
    void observerExceptionRethrown() {
        IllegalStateException boom = new IllegalStateException("observer failed");
        LoadBalancer lb = new LoadBalancer(workers(2), (w, id, units) -> { });

        assertThatThrownBy(() -> new BatchRunner().run(lb, Strategy.ROUND_ROBIN, 100, 1, 4, result -> {
            if (result.requestId() == 3) {
                throw boom;
            }
        })).isSameAs(boom);
        assertThat(lb.workers()).allMatch(w -> w.inFlight() == 0);
        assertThatThrownBy(() -> new BatchRunner().run(lb, Strategy.ROUND_ROBIN, 1, 1, 1, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rejects null arguments with NullPointerException and counts below 1 with IllegalArgumentException")
    void validation() {
        LoadBalancer lb = new LoadBalancer(workers(1), (w, id, units) -> { });
        BatchRunner runner = new BatchRunner();

        assertThatThrownBy(() -> new BatchRunner(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> runner.run(null, Strategy.ROUND_ROBIN, 1, 1, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> runner.run(lb, null, 1, 1, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> runner.run(lb, Strategy.ROUND_ROBIN, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestCount");
        assertThatThrownBy(() -> runner.run(lb, Strategy.ROUND_ROBIN, 1, 0, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("workUnits");
        assertThatThrownBy(() -> runner.run(lb, Strategy.ROUND_ROBIN, 1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("concurrency");
    }
}
