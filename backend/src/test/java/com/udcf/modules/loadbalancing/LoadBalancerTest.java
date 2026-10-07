package com.udcf.modules.loadbalancing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * No test sleeps: latency comes from a hand-advanced clock, and the concurrency tests
 * coordinate with latches and barriers (their timeouts only stop a broken test hanging).
 */
class LoadBalancerTest {

    /** Hang guard for latches and barriers; never part of an assertion. */
    private static final long GUARD_SECONDS = 10;

    /** A nanosecond clock that moves only when a test or a fake transport advances it. */
    private static final class ManualClock implements LongSupplier {
        private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

        void advanceMillis(long millis) {
            nanos.addAndGet(millis * 1_000_000L);
        }

        @Override
        public long getAsLong() {
            return nanos.get();
        }
    }

    private static final WorkerTransport SERVE = (w, id, units) -> { };

    private final ManualClock clock = new ManualClock();
    private ExecutorService testThreads;

    @AfterEach
    void stopTestThreads() {
        if (testThreads != null) {
            testThreads.shutdownNow();
        }
    }

    /** Workers 1..n with the given weights, ports 7201.. */
    private static List<WorkerInfo> workers(int... weights) {
        List<WorkerInfo> list = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) {
            list.add(new WorkerInfo(i + 1, 7201 + i, "W" + (i + 1), weights[i]));
        }
        return list;
    }

    private static WorkerInfo node(LoadBalancer lb, int nodeId) {
        return lb.workers().stream().filter(w -> w.nodeId() == nodeId).findFirst().orElseThrow();
    }

    /** Legacy dispatch did select() and then onDispatch(); scripted pick sequences do the same. */
    private static List<Integer> pickAndReserve(LoadBalancer lb, Strategy strategy, int picks) {
        List<Integer> sequence = new ArrayList<>();
        for (int i = 0; i < picks; i++) {
            WorkerInfo w = lb.select(strategy);
            w.onDispatch();
            sequence.add(w.nodeId());
        }
        return sequence;
    }

    private static List<Integer> picks(LoadBalancer lb, Strategy strategy, int picks) {
        List<Integer> sequence = new ArrayList<>();
        for (int i = 0; i < picks; i++) {
            sequence.add(lb.select(strategy).nodeId());
        }
        return sequence;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(GUARD_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released within the guard");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ================================================================== faithful port

    /**
     * Expected sequences are derived by hand from legacy-demos/exp06-load-balancing
     * LoadBalancer.select and smoothWeighted (not imported; the derivation is in each test).
     */
    @Nested
    @DisplayName("faithful port: the same pick sequences as the legacy select()")
    class FaithfulPort {

        @Test
        @DisplayName("round robin: cursor mod healthy count")
        void roundRobin() {
            LoadBalancer lb = new LoadBalancer(workers(4, 2, 1), SERVE, clock);

            assertThat(picks(lb, Strategy.ROUND_ROBIN, 6)).containsExactly(1, 2, 3, 1, 2, 3);
        }

        @Test
        @DisplayName("round robin keeps the legacy cursor when a worker drops out (index shift included)")
        void roundRobinWithUnhealthy() {
            LoadBalancer lb = new LoadBalancer(workers(4, 2, 1), SERVE, clock);
            List<Integer> sequence = new ArrayList<>(picks(lb, Strategy.ROUND_ROBIN, 2));   // cursor 0, 1
            node(lb, 2).markUnhealthy();
            // healthy [1, 3]: cursor 2 -> index 0 -> 1; cursor 3 -> index 1 -> 3; cursor 4 -> 1
            sequence.addAll(picks(lb, Strategy.ROUND_ROBIN, 3));

            assertThat(sequence).containsExactly(1, 2, 1, 3, 1);
        }

        @Test
        @DisplayName("smooth weighted round robin 4 : 2 : 1 spreads picks 1,2,1,3,1,2,1 and repeats")
        void smoothWeighted() {
            // current weights after adding, then the winner minus 7:
            // [4,2,1]->1 | [1,4,2]->2 | [5,-1,3]->1 | [2,1,4]->3 | [6,3,-2]->1 | [3,5,-1]->2 | [7,0,0]->1
            LoadBalancer lb = new LoadBalancer(workers(4, 2, 1), SERVE, clock);

            assertThat(picks(lb, Strategy.WEIGHTED_ROUND_ROBIN, 14))
                    .containsExactly(1, 2, 1, 3, 1, 2, 1, 1, 2, 1, 3, 1, 2, 1);
        }

        @Test
        @DisplayName("smooth weighted round robin on the 5-node default 4,2,1,2,4: ties go to the first worker")
        void smoothWeightedFiveNodes() {
            // total 13; each line is the weights after adding, the winner, its new weight
            // [4,2,1,2,4]->1 (tie 5),-9 | [-5,4,2,4,8]->5,-5 | [-1,6,3,6,-1]->2 (tie 4),-7
            // [3,-5,4,8,3]->4,-5 | [7,-3,5,-3,7]->1 (tie 5),-6 | [-2,-1,6,-1,11]->5,-2
            // [2,1,7,1,2]->3,-6 | [6,3,-5,3,6]->1,-7 | [-3,5,-4,5,10]->5,-3 | [1,7,-3,7,1]->2,-6
            // [5,-4,-2,9,5]->4,-4 | [9,-2,-1,-2,9]->1,-4 | [0,0,0,0,13]->5,0
            LoadBalancer lb = new LoadBalancer(workers(4, 2, 1, 2, 4), SERVE, clock);

            assertThat(picks(lb, Strategy.WEIGHTED_ROUND_ROBIN, 13))
                    .containsExactly(1, 5, 2, 4, 1, 5, 3, 1, 5, 2, 4, 1, 5);
        }

        @Test
        @DisplayName("least connections: fewest in flight, ties to the lower id")
        void leastConnections() {
            LoadBalancer lb = new LoadBalancer(workers(1, 1, 1), SERVE, clock);
            List<Integer> sequence = new ArrayList<>(pickAndReserve(lb, Strategy.LEAST_CONNECTIONS, 6)); // [2,2,2]
            node(lb, 3).onComplete(1d);                                                // [2,2,1]
            sequence.addAll(pickAndReserve(lb, Strategy.LEAST_CONNECTIONS, 1));        // 3 -> [2,2,2]
            node(lb, 2).onComplete(1d);
            node(lb, 2).onComplete(1d);                                                // [2,0,2]
            sequence.addAll(pickAndReserve(lb, Strategy.LEAST_CONNECTIONS, 3));        // 2, 2, then tie -> 1

            assertThat(sequence).containsExactly(1, 2, 3, 1, 2, 3, 3, 2, 2, 1);
        }

        @Test
        @DisplayName("least response time: lowest EWMA x (in-flight + 1), unmeasured first, ties to the lower id")
        void leastResponseTime() {
            LoadBalancer lb = new LoadBalancer(workers(1, 1, 1), SERVE, clock);
            // Unmeasured cost is 1 x (in-flight + 1): [1,1,1]->1, [2,1,1]->2, [2,2,1]->3
            List<Integer> sequence = new ArrayList<>(pickAndReserve(lb, Strategy.LEAST_RESPONSE_TIME, 3));
            node(lb, 1).onComplete(10d);
            node(lb, 2).onComplete(40d);
            node(lb, 3).onComplete(160d);
            // costs [10,40,160]->1 | [20,40,160]->1 | [30,40,160]->1 | [40,40,160] tie->1
            // [50,40,160]->2 | [50,80,160]->1 | [60,..]->1 | [70,..]->1 | [80,80,160] tie->1 | [90,80,160]->2
            sequence.addAll(pickAndReserve(lb, Strategy.LEAST_RESPONSE_TIME, 10));

            assertThat(sequence).containsExactly(1, 2, 3, 1, 1, 1, 1, 2, 1, 1, 1, 1, 2);
        }

        @Test
        @DisplayName("every strategy returns null when no worker is healthy")
        void noneHealthy() {
            LoadBalancer lb = new LoadBalancer(workers(4, 2, 1), SERVE, clock);
            lb.workers().forEach(WorkerInfo::markUnhealthy);

            for (Strategy s : Strategy.values()) {
                assertThat(lb.select(s)).isNull();
            }
        }
    }

    // ================================================================== deliberate differences

    @Test
    @DisplayName("workers are kept in node-id order, so ties go to the lower id whatever the input order")
    void idOrderAndTies() {
        List<WorkerInfo> reversed = workers(1, 1, 1);
        Collections.reverse(reversed);
        LoadBalancer lb = new LoadBalancer(reversed, SERVE, clock);

        assertThat(lb.workers()).extracting(WorkerInfo::nodeId).containsExactly(1, 2, 3);
        assertThat(lb.select(Strategy.LEAST_RESPONSE_TIME).nodeId()).isEqualTo(1);
        assertThat(lb.select(Strategy.LEAST_CONNECTIONS).nodeId()).isEqualTo(1);
        assertThatThrownBy(() -> lb.workers().add(new WorkerInfo(9, 7209, "W9", 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ================================================================== dispatch

    @Test
    @DisplayName("a served request records the measured latency and releases the in-flight slot")
    void dispatchServed() {
        List<String> sent = new CopyOnWriteArrayList<>();
        LoadBalancer lb = new LoadBalancer(workers(4, 2, 1), (w, id, units) -> {
            sent.add(w.nodeId() + ":" + id + ":" + units);
            clock.advanceMillis(25);
        }, clock);

        DispatchResult r = lb.dispatch(7, 900, Strategy.ROUND_ROBIN);

        assertThat(r).isEqualTo(new DispatchResult(7, 1, 25d, true, 1));
        assertThat(r.rerouted()).isFalse();
        assertThat(sent).containsExactly("1:7:900");
        WorkerInfo w1 = node(lb, 1);
        assertThat(w1.inFlight()).isZero();
        assertThat(w1.completed()).isEqualTo(1);
        assertThat(w1.ewmaLatencyMillis().getAsDouble()).isEqualTo(25d);
    }

    @Test
    @DisplayName("an unreachable worker trips the circuit breaker; the request reroutes and its latency covers both attempts")
    void circuitBreaker() {
        List<Integer> sent = new CopyOnWriteArrayList<>();
        LoadBalancer lb = new LoadBalancer(workers(4, 2, 1), (w, id, units) -> {
            sent.add(w.nodeId());
            if (w.nodeId() == 1) {
                clock.advanceMillis(5);
                throw new ConnectException("Connection refused");
            }
            clock.advanceMillis(20);
        }, clock);

        // Cursor 0 picks 1 (refused); then candidates [2,3], cursor 1 -> 3.
        DispatchResult first = lb.dispatch(1, 10, Strategy.ROUND_ROBIN);

        assertThat(first).isEqualTo(new DispatchResult(1, 3, 25d, true, 2));
        assertThat(first.rerouted()).isTrue();
        WorkerInfo w1 = node(lb, 1);
        assertThat(w1.isHealthy()).isFalse();
        assertThat(w1.failed()).isEqualTo(1);
        assertThat(w1.inFlight()).isZero();
        assertThat(node(lb, 3).ewmaLatencyMillis().getAsDouble()).isEqualTo(20d);   // its own attempt only

        // Node 1 is skipped from now on: candidates [2,3], cursor 2 -> 2, cursor 3 -> 3.
        assertThat(lb.dispatch(2, 10, Strategy.ROUND_ROBIN).nodeId()).isEqualTo(2);
        assertThat(lb.dispatch(3, 10, Strategy.ROUND_ROBIN).nodeId()).isEqualTo(3);
        assertThat(sent).containsExactly(1, 3, 2, 3);
    }

    @Test
    @DisplayName("a declined worker stays healthy, is not retried for the same request, and is tried again for the next")
    void declined() {
        Map<Integer, List<Integer>> triedByRequest = new ConcurrentHashMap<>();
        LoadBalancer lb = new LoadBalancer(workers(1, 1, 1), (w, id, units) -> {
            triedByRequest.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(w.nodeId());
            if (w.nodeId() != 3) {
                throw new WorkerDeclinedException("Queue full");
            }
        }, clock);

        DispatchResult first = lb.dispatch(1, 10, Strategy.LEAST_CONNECTIONS);
        DispatchResult second = lb.dispatch(2, 10, Strategy.LEAST_CONNECTIONS);

        assertThat(first).isEqualTo(new DispatchResult(1, 3, 0d, true, 3));
        assertThat(second.nodeId()).isEqualTo(3);
        assertThat(triedByRequest.get(1)).containsExactly(1, 2, 3);
        assertThat(triedByRequest.get(2)).containsExactly(1, 2, 3);
        for (int id = 1; id <= 2; id++) {
            assertThat(node(lb, id).isHealthy()).isTrue();
            assertThat(node(lb, id).declined()).isEqualTo(2);
            assertThat(node(lb, id).inFlight()).isZero();
        }
    }

    @Test
    @DisplayName("when every worker declines, the request fails after one attempt each and all stay healthy")
    void allDecline() {
        LoadBalancer lb = new LoadBalancer(workers(1, 1, 1), (w, id, units) -> {
            throw new WorkerDeclinedException("Queue full");
        }, clock);

        DispatchResult r = lb.dispatch(1, 10, Strategy.WEIGHTED_ROUND_ROBIN);

        assertThat(r).isEqualTo(new DispatchResult(1, 0, 0d, false, 3));
        assertThat(lb.workers()).allMatch(WorkerInfo::isHealthy).allMatch(w -> w.inFlight() == 0);
    }

    @Test
    @DisplayName("when every worker is unreachable, the request fails after one attempt each with the time spent")
    void allUnreachable() {
        List<Integer> sent = new CopyOnWriteArrayList<>();
        LoadBalancer lb = new LoadBalancer(workers(4, 2, 1), (w, id, units) -> {
            sent.add(w.nodeId());
            clock.advanceMillis(2);
            throw new IOException("closed without a reply");
        }, clock);

        DispatchResult r = lb.dispatch(1, 10, Strategy.LEAST_RESPONSE_TIME);

        assertThat(r).isEqualTo(new DispatchResult(1, 0, 6d, false, 3));
        assertThat(sent).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(lb.workers()).noneMatch(WorkerInfo::isHealthy).allMatch(w -> w.inFlight() == 0);
    }

    // ================================================================== reroute termination

    @Nested
    @DisplayName("reroute termination")
    class Termination {

        @Test
        @DisplayName("one worker only: served, unreachable and declined each take exactly one attempt")
        void oneWorker() {
            LoadBalancer served = new LoadBalancer(workers(1), SERVE, clock);
            LoadBalancer unreachable = new LoadBalancer(workers(1), (w, id, u) -> {
                throw new ConnectException("refused");
            }, clock);
            LoadBalancer declined = new LoadBalancer(workers(1), (w, id, u) -> {
                throw new WorkerDeclinedException("Queue full");
            }, clock);

            assertThat(served.dispatch(1, 1, Strategy.ROUND_ROBIN)).isEqualTo(new DispatchResult(1, 1, 0d, true, 1));

            DispatchResult down = unreachable.dispatch(1, 1, Strategy.ROUND_ROBIN);
            assertThat(down).isEqualTo(new DispatchResult(1, 0, 0d, false, 1));
            assertThat(down.rerouted()).isFalse();
            assertThat(node(unreachable, 1).isHealthy()).isFalse();

            assertThat(declined.dispatch(1, 1, Strategy.ROUND_ROBIN)).isEqualTo(new DispatchResult(1, 0, 0d, false, 1));
            assertThat(node(declined, 1).isHealthy()).isTrue();
        }

        @Test
        @DisplayName("all workers unhealthy at the start: no attempt, the transport is never called")
        void allUnhealthyAtStart() {
            List<Integer> sent = new CopyOnWriteArrayList<>();
            LoadBalancer lb = new LoadBalancer(workers(4, 2, 1), (w, id, u) -> sent.add(w.nodeId()), clock);
            lb.workers().forEach(WorkerInfo::markUnhealthy);

            for (Strategy s : Strategy.values()) {
                assertThat(lb.dispatch(1, 1, s)).isEqualTo(new DispatchResult(1, 0, 0d, false, 0));
            }
            assertThat(sent).isEmpty();
            assertThat(lb.workers()).allMatch(w -> w.inFlight() == 0);
        }

        @Test
        @DisplayName("a worker that becomes unhealthy while another request is in flight on it")
        void unhealthyDuringAnotherDispatch() throws Exception {
            CountDownLatch aEntered = new CountDownLatch(1);
            CountDownLatch releaseA = new CountDownLatch(1);
            LoadBalancer lb = new LoadBalancer(workers(1, 1), (w, id, u) -> {
                if (id == 1) {               // request A: held on node 1
                    aEntered.countDown();
                    await(releaseA);
                    return;
                }
                throw new ConnectException("refused");   // request B: every attempt refused
            }, clock);
            testThreads = Executors.newSingleThreadExecutor();

            Future<DispatchResult> a = testThreads.submit(() -> lb.dispatch(1, 1, Strategy.ROUND_ROBIN));
            await(aEntered);
            // B: cursor 1 -> node 2 refused; then only node 1 left -> refused while A is on it.
            DispatchResult b = lb.dispatch(2, 1, Strategy.ROUND_ROBIN);

            assertThat(b).isEqualTo(new DispatchResult(2, 0, 0d, false, 2));
            WorkerInfo w1 = node(lb, 1);
            assertThat(w1.isHealthy()).isFalse();
            assertThat(w1.inFlight()).isEqualTo(1);           // A is still out there

            releaseA.countDown();
            assertThat(a.get(GUARD_SECONDS, TimeUnit.SECONDS)).isEqualTo(new DispatchResult(1, 1, 0d, true, 1));
            assertThat(w1.inFlight()).isZero();
            assertThat(w1.completed()).isEqualTo(1);
            assertThat(w1.failed()).isEqualTo(1);
            assertThat(lb.select(Strategy.ROUND_ROBIN)).isNull();
        }

        @Test
        @DisplayName("resetForRun while a request is in flight marks workers healthy and never drives in-flight negative")
        void resetWhileInFlight() throws Exception {
            CountDownLatch aEntered = new CountDownLatch(1);
            CountDownLatch releaseA = new CountDownLatch(1);
            LoadBalancer lb = new LoadBalancer(workers(1, 1), (w, id, u) -> {
                if (id == 1) {
                    aEntered.countDown();
                    await(releaseA);
                    return;
                }
                if (w.nodeId() == 2) {
                    throw new ConnectException("refused");
                }
            }, clock);
            testThreads = Executors.newSingleThreadExecutor();

            Future<DispatchResult> a = testThreads.submit(() -> lb.dispatch(1, 1, Strategy.ROUND_ROBIN));
            await(aEntered);
            // B: node 2 refused (now unhealthy), rerouted to node 1 alongside A.
            assertThat(lb.dispatch(2, 1, Strategy.ROUND_ROBIN).nodeId()).isEqualTo(1);
            assertThat(node(lb, 2).isHealthy()).isFalse();

            assertThatCode(lb::resetForRun).doesNotThrowAnyException();

            assertThat(node(lb, 2).isHealthy()).isTrue();
            assertThat(node(lb, 1).inFlight()).isEqualTo(1);   // A is live state, kept
            assertThat(node(lb, 1).completed()).isZero();
            releaseA.countDown();
            assertThat(a.get(GUARD_SECONDS, TimeUnit.SECONDS).succeeded()).isTrue();
            assertThat(node(lb, 1).inFlight()).isZero();
            assertThat(node(lb, 1).completed()).isEqualTo(1);   // counted in the new run
        }
    }

    // ================================================================== thread safety

    @ParameterizedTest
    @EnumSource(value = Strategy.class, names = {"LEAST_CONNECTIONS", "LEAST_RESPONSE_TIME"})
    @DisplayName("choosing and counting in flight is one step: 12 concurrent requests split exactly 4/4/4")
    void atomicReservation(Strategy strategy) throws Exception {
        // All 12 requests are held in the transport until every one has arrived, so all 12
        // are in flight at once. Each pick sees every earlier reservation, so the k-th pick
        // goes to a worker with the fewest reserved: exactly 4 each. If choosing and counting
        // were separate steps, two threads could both pick the same "least busy" worker.
        int clients = 12;
        Map<Integer, Integer> inFlightAtBarrier = new ConcurrentHashMap<>();
        List<LoadBalancer> holder = new ArrayList<>(1);
        CyclicBarrier allInFlight = new CyclicBarrier(clients, () ->
                holder.get(0).workers().forEach(w -> inFlightAtBarrier.put(w.nodeId(), w.inFlight())));
        LoadBalancer lb = new LoadBalancer(workers(1, 1, 1), (w, id, u) -> {
            try {
                allInFlight.await(GUARD_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                throw new IllegalStateException(e);
            }
        }, clock);
        holder.add(lb);
        testThreads = Executors.newFixedThreadPool(clients);

        List<Future<DispatchResult>> futures = new ArrayList<>();
        for (int i = 1; i <= clients; i++) {
            int id = i;
            futures.add(testThreads.submit(() -> lb.dispatch(id, 1, strategy)));
        }
        Map<Integer, Integer> served = new ConcurrentHashMap<>();
        for (Future<DispatchResult> f : futures) {
            DispatchResult r = f.get(GUARD_SECONDS, TimeUnit.SECONDS);
            assertThat(r.succeeded()).isTrue();
            served.merge(r.nodeId(), 1, Integer::sum);
        }

        assertThat(inFlightAtBarrier).containsExactlyInAnyOrderEntriesOf(Map.of(1, 4, 2, 4, 3, 4));
        assertThat(served).containsExactlyInAnyOrderEntriesOf(Map.of(1, 4, 2, 4, 3, 4));
        assertThat(lb.workers()).allMatch(w -> w.inFlight() == 0);
    }

    @Test
    @DisplayName("12 client threads x 50 requests: all 600 served, no count lost, nothing left in flight")
    void concurrentDispatch() throws Exception {
        LoadBalancer lb = new LoadBalancer(workers(4, 2, 1), SERVE, clock);
        testThreads = Executors.newFixedThreadPool(12);

        List<Future<List<DispatchResult>>> futures = new ArrayList<>();
        for (int t = 0; t < 12; t++) {
            int base = t * 50;
            futures.add(testThreads.submit(() -> {
                List<DispatchResult> mine = new ArrayList<>();
                for (int i = 1; i <= 50; i++) {
                    mine.add(lb.dispatch(base + i, 900, Strategy.LEAST_CONNECTIONS));
                }
                return mine;
            }));
        }
        List<DispatchResult> all = new ArrayList<>();
        for (Future<List<DispatchResult>> f : futures) {
            all.addAll(f.get(GUARD_SECONDS, TimeUnit.SECONDS));
        }

        assertThat(all).hasSize(600).allMatch(DispatchResult::succeeded);
        assertThat(lb.workers().stream().mapToInt(WorkerInfo::completed).sum()).isEqualTo(600);
        assertThat(lb.workers()).allMatch(w -> w.inFlight() == 0);
    }

    // ================================================================== transport bugs, reset, validation

    @Test
    @DisplayName("a RuntimeException from the transport propagates and releases the in-flight slot")
    void transportRuntimeException() {
        IllegalStateException bug = new IllegalStateException("transport bug");
        LoadBalancer lb = new LoadBalancer(workers(4, 2, 1), (w, id, u) -> {
            throw bug;
        }, clock);

        assertThatThrownBy(() -> lb.dispatch(1, 1, Strategy.LEAST_CONNECTIONS)).isSameAs(bug);

        WorkerInfo w1 = node(lb, 1);
        assertThat(w1.inFlight()).isZero();
        assertThat(w1.isHealthy()).isTrue();
        assertThat(w1.failed()).isZero();
        assertThat(w1.declined()).isZero();
        assertThat(w1.completed()).isZero();
    }

    @Test
    @DisplayName("resetForRun restores health, the round robin cursor, smooth weights and counters")
    void resetForRun() {
        LoadBalancer lb = new LoadBalancer(workers(4, 2, 1), SERVE, clock);
        lb.dispatch(1, 1, Strategy.ROUND_ROBIN);
        lb.dispatch(2, 1, Strategy.ROUND_ROBIN);
        picks(lb, Strategy.WEIGHTED_ROUND_ROBIN, 3);
        node(lb, 3).markUnhealthy();

        lb.resetForRun();

        assertThat(lb.workers()).allMatch(WorkerInfo::isHealthy).allMatch(w -> w.completed() == 0);
        assertThat(picks(lb, Strategy.ROUND_ROBIN, 3)).containsExactly(1, 2, 3);
        assertThat(picks(lb, Strategy.WEIGHTED_ROUND_ROBIN, 7)).containsExactly(1, 2, 1, 3, 1, 2, 1);
    }

    @Test
    @DisplayName("rejects null arguments with NullPointerException and bad values with IllegalArgumentException")
    void validation() {
        List<WorkerInfo> ok = workers(1);
        assertThatThrownBy(() -> new LoadBalancer(null, SERVE, clock)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LoadBalancer(ok, null, clock)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LoadBalancer(ok, SERVE, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LoadBalancer(null, SERVE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LoadBalancer(Arrays.asList(ok.get(0), null), SERVE, clock))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LoadBalancer(List.of(), SERVE, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> new LoadBalancer(
                List.of(new WorkerInfo(1, 7201, "A", 1), new WorkerInfo(1, 7202, "B", 1)), SERVE, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");

        LoadBalancer lb = new LoadBalancer(ok, SERVE, clock);
        assertThatThrownBy(() -> lb.dispatch(0, 1, Strategy.ROUND_ROBIN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestId");
        assertThatThrownBy(() -> lb.dispatch(1, 0, Strategy.ROUND_ROBIN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("workUnits");
        assertThatThrownBy(() -> lb.dispatch(1, 1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> lb.select(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the default constructor measures with the real monotonic clock")
    void realClock() {
        LoadBalancer lb = new LoadBalancer(workers(1), SERVE);

        DispatchResult r = lb.dispatch(1, 1, Strategy.ROUND_ROBIN);

        assertThat(r.succeeded()).isTrue();
        assertThat(r.latencyMillis()).isGreaterThanOrEqualTo(0d);
    }
}
