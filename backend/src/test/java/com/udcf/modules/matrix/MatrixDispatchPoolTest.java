package com.udcf.modules.matrix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The root's compute callers: concurrent, named daemon threads, closed within the 5 s bound. */
class MatrixDispatchPoolTest {

    @Test
    @DisplayName("tasks run at the same time: three tasks each wait at one latch for all three, and all pass")
    void concurrent() throws Exception {
        try (MatrixDispatchPool pool = new MatrixDispatchPool(3)) {
            CountDownLatch together = new CountDownLatch(3);
            List<CompletableFuture<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                results.add(pool.submit(() -> {
                    together.countDown();
                    return together.await(10, TimeUnit.SECONDS);
                }));
            }
            for (CompletableFuture<Boolean> result : results) {
                assertThat(result.get(15, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(pool.threads()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("threads are prestarted daemons named udcf-matrix-dispatch-<i>; a task's exception completes its future")
    void namedDaemonsAndFailures() throws Exception {
        try (MatrixDispatchPool pool = new MatrixDispatchPool(2)) {
            Thread thread = pool.submit(Thread::currentThread).get(10, TimeUnit.SECONDS);
            assertThat(thread.getName()).matches("udcf-matrix-dispatch-[01]");
            assertThat(thread.isDaemon()).isTrue();
            CompletableFuture<Object> failing = pool.submit(() -> {
                throw new IllegalStateException("boom");
            });
            assertThatThrownBy(() -> failing.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("close interrupts a blocked task, ends every thread and refuses new tasks")
    void closeInterruptsAndJoins() throws Exception {
        MatrixDispatchPool pool = new MatrixDispatchPool(2);
        CountDownLatch never = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        CompletableFuture<Boolean> blocked = pool.submit(() -> {
            worker.set(Thread.currentThread());
            entered.countDown();
            return never.await(30, TimeUnit.SECONDS);
        });
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        pool.close();

        assertThatThrownBy(() -> blocked.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(InterruptedException.class);
        worker.get().join(TimeUnit.SECONDS.toMillis(5));
        assertThat(worker.get().isAlive()).isFalse();
        assertThatThrownBy(() -> pool.submit(() -> 1)).isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    @DisplayName("a pool needs at least one thread")
    void size() {
        assertThatThrownBy(() -> new MatrixDispatchPool(0)).hasMessage("threads must be >= 1, was 0");
    }
}
