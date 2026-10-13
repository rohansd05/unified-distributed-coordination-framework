package com.udcf.modules.matrix;

import com.udcf.core.cluster.NodeDownException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * {@link NodeExecutor#multiply}: the time is taken inside the task around the multiply only, every executor
 * failure has its reason, and an abandoned task never starts its multiply later (condition 4 of the E10b
 * review). Executor doubles only; no sleeps.
 */
class NodeExecutorTest {

    private static final int N = 4;
    private static final double[] A = MatrixGenerator.a(1, N);
    private static final double[] B = MatrixGenerator.b(1, N);

    /** A stepping clock that counts its reads. */
    private static final class Counting implements LongSupplier {
        final AtomicLong now = new AtomicLong();
        final AtomicInteger reads = new AtomicInteger();

        @Override
        public long getAsLong() {
            reads.incrementAndGet();
            return now.getAndAdd(1000);
        }
    }

    /** The node's refusal thrown by {@code call}. */
    private static MatrixComputeException refusal(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        return catchThrowableOfType(call, MatrixComputeException.class);
    }

    private static MatrixComputeException.Reason reasonOf(NodeExecutor executor, long timeoutMillis) {
        return refusal(
                () -> executor.multiply(A, B, N, timeoutMillis, () -> 0)).reason();
    }

    private static MatrixMultiply.Timed call(Callable<MatrixMultiply.Timed> task) {
        try {
            return task.call();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("the multiply is timed inside the task only: time spent queued before it starts is not part of it")
    void queueWaitExcluded() throws Exception {
        Counting clock = new Counting();
        NodeExecutor queued = task -> CompletableFuture.supplyAsync(() -> {
            clock.now.addAndGet(5_000_000);   // time passes while the task waits in the queue
            return call(task);
        });

        MatrixMultiply.Timed timed = queued.multiply(A, B, N, 10_000, clock);

        assertThat(timed.nanos()).isEqualTo(1000);
        assertThat(clock.reads.get()).isEqualTo(2);
        assertThat(timed.values()).isEqualTo(MatrixMultiply.multiply(A, B, N));
    }

    @Test
    @DisplayName("a task still queued when the wait times out never starts its multiply, and its future is cancelled")
    void queuedTaskNeverStartsLater() {
        Counting clock = new Counting();
        AtomicReference<Callable<MatrixMultiply.Timed>> queuedTask = new AtomicReference<>();
        AtomicReference<CompletableFuture<MatrixMultiply.Timed>> handed = new AtomicReference<>();
        NodeExecutor blocked = task -> {
            queuedTask.set(task);
            handed.set(new CompletableFuture<>());
            return handed.get();
        };

        MatrixComputeException timedOut = refusal(
                () -> blocked.multiply(A, B, N, 30, clock));
        assertThat(timedOut.reason()).isEqualTo(MatrixComputeException.Reason.COMPUTE_TIMED_OUT);
        assertThat(timedOut.getMessage()).isEqualTo("The multiply did not finish within 30 ms, queue wait included.");
        assertThat(handed.get().isCancelled()).isTrue();

        assertThatThrownBy(() -> queuedTask.get().call()).isInstanceOf(CancellationException.class)
                .hasMessage("the caller stopped waiting before this multiply started");
        assertThat(clock.reads.get()).isZero();   // the multiply never ran
    }

    @Test
    @DisplayName("a task that had already started runs to the end; its result is discarded (its future was cancelled)")
    void startedTaskRunsToTheEnd() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<CompletableFuture<MatrixMultiply.Timed>> handed = new AtomicReference<>();
        AtomicReference<MatrixMultiply.Timed> produced = new AtomicReference<>();
        LongSupplier blockingClock = () -> {
            started.countDown();
            try {
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        };
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            NodeExecutor running = task -> {
                CompletableFuture<MatrixMultiply.Timed> future = new CompletableFuture<>();
                handed.set(future);
                worker.execute(() -> {
                    MatrixMultiply.Timed result = call(task);
                    produced.set(result);
                    future.complete(result);
                    finished.countDown();
                });
                try {
                    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();   // inside the multiply before the wait begins
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return future;
            };

            MatrixComputeException timedOut = refusal(
                    () -> running.multiply(A, B, N, 50, blockingClock));
            assertThat(timedOut.reason()).isEqualTo(MatrixComputeException.Reason.COMPUTE_TIMED_OUT);

            release.countDown();
            assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(produced.get().values()).isEqualTo(MatrixMultiply.multiply(A, B, N));
            assertThat(handed.get().isCancelled()).isTrue();   // the result never reached anyone
        }
    }

    @Test
    @DisplayName("each executor failure has its reason: down or not startable, rejected, down later, failed, cancelled")
    void reasons() {
        assertThat(reasonOf(task -> {
            throw new NodeDownException(3);
        }, 1000)).isEqualTo(MatrixComputeException.Reason.EXECUTOR_UNAVAILABLE);
        assertThat(reasonOf(task -> {
            throw new IllegalStateException("Service 'requests' failed to start on node 3");
        }, 1000)).isEqualTo(MatrixComputeException.Reason.EXECUTOR_UNAVAILABLE);
        assertThat(reasonOf(task -> CompletableFuture.failedFuture(new RejectedExecutionException("full")), 1000))
                .isEqualTo(MatrixComputeException.Reason.EXECUTOR_REJECTED);
        assertThat(reasonOf(task -> CompletableFuture.failedFuture(new NodeDownException(3)), 1000))
                .isEqualTo(MatrixComputeException.Reason.EXECUTOR_UNAVAILABLE);
        assertThat(reasonOf(task -> CompletableFuture.failedFuture(new ArithmeticException()), 1000))
                .isEqualTo(MatrixComputeException.Reason.COMPUTE_FAILED);
        CompletableFuture<MatrixMultiply.Timed> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        assertThat(reasonOf(task -> cancelled, 1000)).isEqualTo(MatrixComputeException.Reason.COMPUTE_FAILED);
        assertThatThrownBy(() -> InMemoryMatrixNodes.DIRECT.multiply(A, B, N, 0, () -> 0))
                .hasMessage("timeoutMillis must be >= 1, was 0");
    }

    @Test
    @DisplayName("interrupted while waiting: COMPUTE_FAILED, the task abandoned, the interrupt flag kept")
    void interrupted() {
        AtomicReference<CompletableFuture<MatrixMultiply.Timed>> handed = new AtomicReference<>();
        NodeExecutor blocked = task -> {
            handed.set(new CompletableFuture<>());
            return handed.get();
        };
        Thread.currentThread().interrupt();
        MatrixComputeException failed = refusal(
                () -> blocked.multiply(A, B, N, 10_000, () -> 0));

        assertThat(Thread.interrupted()).isTrue();   // also clears it for the next test
        assertThat(failed.reason()).isEqualTo(MatrixComputeException.Reason.COMPUTE_FAILED);
        assertThat(handed.get().isCancelled()).isTrue();
    }
}
