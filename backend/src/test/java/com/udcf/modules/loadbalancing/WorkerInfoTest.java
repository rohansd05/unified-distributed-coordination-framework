package com.udcf.modules.loadbalancing;

import com.udcf.core.cluster.NodeCapacity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class WorkerInfoTest {

    private static WorkerInfo worker() {
        return new WorkerInfo(1, 7201, "FAST", 4);
    }

    @Test
    @DisplayName("of(capacity) uses the static 4 : 2 : 1 thread-count weights and the profile name")
    void ofCapacity() {
        WorkerInfo fast = WorkerInfo.of(1, 7201, NodeCapacity.FAST);
        WorkerInfo medium = WorkerInfo.of(2, 7202, NodeCapacity.MEDIUM);
        WorkerInfo slow = WorkerInfo.of(3, 7203, NodeCapacity.SLOW);

        assertThat(List.of(fast.weight(), medium.weight(), slow.weight())).containsExactly(4, 2, 1);
        assertThat(List.of(fast.label(), medium.label(), slow.label())).containsExactly("FAST", "MEDIUM", "SLOW");
        assertThat(slow.nodeId()).isEqualTo(3);
        assertThat(slow.port()).isEqualTo(7203);
    }

    @Test
    @DisplayName("rejects invalid construction")
    void validation() {
        assertThatThrownBy(() -> new WorkerInfo(0, 7201, "X", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerInfo(1, 0, "X", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerInfo(1, 65_536, "X", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerInfo(1, 7201, " ", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerInfo(1, 7201, "X", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkerInfo(1, 7201, null, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WorkerInfo.of(1, 7201, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("every outcome releases the in-flight slot and counts itself")
    void outcomes() {
        WorkerInfo w = worker();
        for (int i = 0; i < 4; i++) {
            w.onDispatch();
        }
        assertThat(w.inFlight()).isEqualTo(4);

        w.onComplete(10d);
        w.onFailure();
        w.onDeclined();
        w.onAborted();

        assertThat(w.inFlight()).isZero();
        assertThat(w.completed()).isEqualTo(1);
        assertThat(w.failed()).isEqualTo(1);
        assertThat(w.declined()).isEqualTo(1);
    }

    @Test
    @DisplayName("the first reply seeds the EWMA, then each reply weighs 0.3 against 0.7 of the history")
    void ewma() {
        WorkerInfo w = worker();
        assertThat(w.ewmaLatencyMillis()).isEmpty();
        assertThat(w.averageLatencyMillis()).isEmpty();

        w.onDispatch();
        w.onComplete(10d);
        assertThat(w.ewmaLatencyMillis().getAsDouble()).isEqualTo(10d);

        w.onDispatch();
        w.onComplete(20d);                      // 0.3 * 20 + 0.7 * 10
        assertThat(w.ewmaLatencyMillis().getAsDouble()).isCloseTo(13d, within(1e-9));

        w.onDispatch();
        w.onComplete(40d);                      // 0.3 * 40 + 0.7 * 13
        assertThat(w.ewmaLatencyMillis().getAsDouble()).isCloseTo(21.1d, within(1e-9));
        assertThat(w.averageLatencyMillis().getAsDouble()).isCloseTo(70d / 3, within(1e-9));
    }

    @Test
    @DisplayName("a 0 ms first reply is a real sample, so the next reply blends instead of re-seeding")
    void zeroSampleDoesNotReseed() {
        WorkerInfo w = worker();
        w.onDispatch();
        w.onComplete(0d);
        w.onDispatch();
        w.onComplete(10d);

        // Legacy used 0.0 as "no sample yet" and would have re-seeded to 10.
        assertThat(w.ewmaLatencyMillis().getAsDouble()).isCloseTo(3d, within(1e-9));
    }

    @Test
    @DisplayName("estimated cost is the EWMA times (in-flight + 1), with base 1 before any reply")
    void estimatedCost() {
        WorkerInfo w = worker();
        assertThat(w.estimatedCost()).isEqualTo(1d);
        w.onDispatch();
        w.onDispatch();
        assertThat(w.estimatedCost()).isEqualTo(3d);

        w.onComplete(10d);                      // EWMA 10, one still in flight
        assertThat(w.estimatedCost()).isEqualTo(20d);
    }

    @Test
    @DisplayName("rejects a negative or non-finite latency")
    void rejectsBadLatency() {
        WorkerInfo w = worker();
        w.onDispatch();
        assertThatThrownBy(() -> w.onComplete(-1d)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> w.onComplete(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThat(w.inFlight()).isEqualTo(1);
    }

    @Test
    @DisplayName("resetCounters clears the run's counters and smooth weight but keeps live in-flight requests")
    void resetCounters() {
        WorkerInfo w = worker();
        w.onDispatch();
        w.onComplete(5d);
        w.onDispatch();
        w.onFailure();
        w.onDispatch();
        w.onDeclined();
        w.setCurrentWeight(-3);
        w.onDispatch();                         // still outstanding

        w.resetCounters();

        assertThat(w.completed()).isZero();
        assertThat(w.failed()).isZero();
        assertThat(w.declined()).isZero();
        assertThat(w.ewmaLatencyMillis()).isEmpty();
        assertThat(w.averageLatencyMillis()).isEmpty();
        assertThat(w.currentWeight()).isZero();
        assertThat(w.inFlight()).isEqualTo(1);
    }

    @Test
    @DisplayName("health is set and cleared by the circuit breaker")
    void health() {
        WorkerInfo w = worker();
        assertThat(w.isHealthy()).isTrue();
        w.markUnhealthy();
        assertThat(w.isHealthy()).isFalse();
        w.markHealthy();
        assertThat(w.isHealthy()).isTrue();
    }

    @Test
    @DisplayName("16 threads x 1000 dispatch/complete pairs lose no count and no EWMA update")
    void concurrentBookkeeping() throws Exception {
        WorkerInfo w = worker();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 1000; i++) {
                        w.onDispatch();
                        w.onComplete(1d);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(w.completed()).isEqualTo(16_000);
        assertThat(w.inFlight()).isZero();
        assertThat(w.averageLatencyMillis().getAsDouble()).isEqualTo(1d);
        assertThat(w.ewmaLatencyMillis().getAsDouble()).isCloseTo(1d, within(1e-9));
    }
}
