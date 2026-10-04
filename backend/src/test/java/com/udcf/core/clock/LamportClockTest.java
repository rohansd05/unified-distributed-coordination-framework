package com.udcf.core.clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards Lamport's three rules and, above all, that they hold under contention: a lost
 * update would let a node's clock run backwards and break causal order cluster-wide.
 */
class LamportClockTest {

    @Test
    @DisplayName("starts at zero")
    void startsAtZero() {
        assertThat(new LamportClock().current()).isZero();
    }

    @Test
    @DisplayName("tick increments by one on a local event")
    void tickIncrements() {
        LamportClock clock = new LamportClock();

        assertThat(clock.tick()).isEqualTo(1);
        assertThat(clock.tick()).isEqualTo(2);
        assertThat(clock.current()).isEqualTo(2);
    }

    @Test
    @DisplayName("current reads without advancing the clock")
    void currentDoesNotAdvance() {
        LamportClock clock = new LamportClock();
        clock.tick();

        assertThat(clock.current()).isEqualTo(1);
        assertThat(clock.current()).isEqualTo(1);
    }

    @Test
    @DisplayName("send ticks and returns the value attached to the message")
    void sendTicksAndReturnsAttachedValue() {
        LamportClock clock = new LamportClock();
        clock.tick();

        long attached = clock.tick();

        assertThat(attached).isEqualTo(2);
        assertThat(clock.current()).isEqualTo(attached);
    }

    @Test
    @DisplayName("receive jumps to remote + 1 when the remote clock is ahead")
    void receiveWhenRemoteAhead() {
        LamportClock clock = new LamportClock();
        clock.tick();

        assertThat(clock.update(10)).isEqualTo(11);
        assertThat(clock.current()).isEqualTo(11);
    }

    @Test
    @DisplayName("receive gives local + 1 when the remote clock is behind")
    void receiveWhenRemoteBehind() {
        LamportClock clock = new LamportClock();
        for (int i = 0; i < 5; i++) {
            clock.tick();
        }

        assertThat(clock.update(2)).isEqualTo(6);
    }

    @Test
    @DisplayName("receive gives value + 1 when both clocks are equal")
    void receiveWhenEqual() {
        LamportClock clock = new LamportClock();
        clock.update(4);

        assertThat(clock.update(5)).isEqualTo(6);
    }

    @Test
    @DisplayName("reset returns the clock to zero")
    void resetReturnsToZero() {
        LamportClock clock = new LamportClock();
        clock.update(42);

        clock.reset();

        assertThat(clock.current()).isZero();
    }

    @Test
    @DisplayName("50 threads x 1000 ticks lose no update and never repeat a value")
    void fiftyThreadsTickWithoutLoss() throws Exception {
        int threads = 50;
        int ticksPerThread = 1000;
        LamportClock clock = new LamportClock();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<long[]>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    long[] values = new long[ticksPerThread];
                    for (int i = 0; i < ticksPerThread; i++) {
                        values[i] = clock.tick();
                    }
                    return values;
                }));
            }
            start.countDown();

            Set<Long> distinct = new HashSet<>();
            for (Future<long[]> future : futures) {
                for (long value : future.get(30, TimeUnit.SECONDS)) {
                    distinct.add(value);
                }
            }

            assertThat(clock.current()).isEqualTo(50_000);
            assertThat(distinct).hasSize(50_000);
        } finally {
            pool.shutdownNow();
        }
    }

    /** What one thread saw: every value the clock returned, and the largest value it received. */
    private record ThreadRun(long[] values, long maxReceived) {
    }

    @Test
    @DisplayName("concurrent receive and tick stay causal: past every received value, monotonic per thread")
    void concurrentReceiveAndTickStayCausal() throws Exception {
        int threads = 50;
        int opsPerThread = 1000;
        LamportClock clock = new LamportClock();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ThreadRun>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Random random = new Random(1000L + t);
                futures.add(pool.submit(() -> {
                    start.await();
                    long[] values = new long[opsPerThread];
                    long maxReceived = 0;
                    for (int i = 0; i < opsPerThread; i++) {
                        if (random.nextBoolean()) {
                            long received = random.nextInt(200_000);
                            maxReceived = Math.max(maxReceived, received);
                            values[i] = clock.update(received);
                        } else {
                            values[i] = clock.tick();
                        }
                    }
                    return new ThreadRun(values, maxReceived);
                }));
            }
            start.countDown();

            long highestReceived = 0;
            for (Future<ThreadRun> future : futures) {
                ThreadRun run = future.get(30, TimeUnit.SECONDS);
                highestReceived = Math.max(highestReceived, run.maxReceived());
                for (int i = 1; i < run.values().length; i++) {
                    assertThat(run.values()[i]).isGreaterThan(run.values()[i - 1]);
                }
            }

            assertThat(clock.current()).isGreaterThan(highestReceived);
        } finally {
            pool.shutdownNow();
        }
    }
}
