package com.udcf.modules.matrix;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The root's callers for the compute phase (step E10b): one thread per rank, so every node's compute is
 * asked for at the same time. Without it the nodes would compute one after another and the parallel run
 * could never beat the sequential one, by construction.
 *
 * <p>Sized by the caller (E10c: {@code udcf.cluster.size}, the most ranks a run can have). The threads are
 * started at construction, so no run pays for creating them, and are daemons named
 * {@code udcf-matrix-dispatch-<i>}. {@link #close()} interrupts them and waits at most 5 s.</p>
 */
public final class MatrixDispatchPool implements AutoCloseable {

    /** How long {@link #close()} waits for the threads to end. A shutdown bound, not deployment config. */
    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final int threads;
    private final ThreadPoolExecutor executor;

    public MatrixDispatchPool(int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1, was " + threads);
        }
        this.threads = threads;
        // Unbounded queue: at most one task per rank per run, and runs never overlap (E10c's guard).
        this.executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                Thread.ofPlatform().name("udcf-matrix-dispatch-", 0).daemon().factory());
        executor.prestartAllCoreThreads();
    }

    public int threads() {
        return threads;
    }

    /**
     * Runs {@code task} on a pool thread; the future completes with its result or its exception.
     *
     * @throws java.util.concurrent.RejectedExecutionException after {@link #close()}
     */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task must not be null");
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Interrupts the threads and waits for them to end.
     *
     * @throws IllegalStateException if a thread is still alive after 5 s, or the wait is interrupted (the
     *                               interrupt flag is restored)
     */
    @Override
    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("matrix dispatch threads did not end within " + CLOSE_TIMEOUT.toMillis() + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the matrix dispatch threads to end", e);
        }
    }
}
