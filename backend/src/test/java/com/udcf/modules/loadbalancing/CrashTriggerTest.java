package com.udcf.modules.loadbalancing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The crash plan's trigger: exactly once, on the thread that serves the chosen request. No sleeps. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CrashTriggerTest {

    private static DispatchResult served(int id) {
        return new DispatchResult(id, 1, 1d, true, 1);
    }

    private static DispatchResult failed(int id) {
        return new DispatchResult(id, 0, 1d, false, 3);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 6, 12})
    @DisplayName("12 clients released together by a barrier: the crash is called exactly once")
    void exactlyOnceUnderContention(int afterServed) throws Exception {
        int clients = 12;
        AtomicInteger crashCalls = new AtomicInteger();
        List<String> crashThreads = new CopyOnWriteArrayList<>();
        List<Boolean> reported = new CopyOnWriteArrayList<>();
        CrashTrigger trigger = new CrashTrigger(afterServed, () -> {
            crashCalls.incrementAndGet();
            crashThreads.add(Thread.currentThread().getName());
            return true;
        }, reported::add);
        CyclicBarrier together = new CyclicBarrier(clients);
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int c = 0; c < clients; c++) {
                int id = c + 1;
                futures.add(pool.submit(() -> {
                    together.await(10, TimeUnit.SECONDS);   // all 12 report a served request at once
                    trigger.accept(served(id));
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(crashCalls).hasValue(1);
        assertThat(crashThreads).hasSize(1);
        assertThat(reported).containsExactly(true);
        assertThat(trigger.triggered()).isTrue();
        assertThat(trigger.crashed()).isTrue();
    }

    @Test
    @DisplayName("requests no worker served do not count towards afterServed")
    void failuresDoNotCount() {
        AtomicInteger crashCalls = new AtomicInteger();
        CrashTrigger trigger = new CrashTrigger(2, () -> crashCalls.incrementAndGet() > 0, crashed -> { });

        trigger.accept(failed(1));
        trigger.accept(served(2));
        trigger.accept(failed(3));
        assertThat(crashCalls).hasValue(0);
        trigger.accept(served(4));
        assertThat(crashCalls).hasValue(1);
        trigger.accept(served(5));
        assertThat(crashCalls).hasValue(1);
    }

    @Test
    @DisplayName("a node already down when the request is served: triggered, but crashed is false")
    void alreadyDown() {
        List<Boolean> reported = new ArrayList<>();
        CrashTrigger trigger = new CrashTrigger(1, () -> false, reported::add);

        trigger.accept(served(1));

        assertThat(trigger.triggered()).isTrue();
        assertThat(trigger.crashed()).isFalse();
        assertThat(reported).containsExactly(false);
    }

    @Test
    @DisplayName("never reaching afterServed: no crash, not triggered")
    void neverReached() {
        CrashTrigger trigger = new CrashTrigger(10, () -> true, crashed -> { });

        trigger.accept(served(1));

        assertThat(trigger.triggered()).isFalse();
        assertThat(trigger.crashed()).isFalse();
    }

    @Test
    @DisplayName("rejects afterServed below 1 and null callbacks")
    void validation() {
        assertThatThrownBy(() -> new CrashTrigger(0, () -> true, c -> { })).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CrashTrigger(1, null, c -> { })).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CrashTrigger(1, () -> true, null)).isInstanceOf(NullPointerException.class);
    }
}
