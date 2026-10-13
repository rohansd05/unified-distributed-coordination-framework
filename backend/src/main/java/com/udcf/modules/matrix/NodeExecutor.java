package com.udcf.modules.matrix;

import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeDownException;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.modules.multithreading.MultithreadingProperties;
import com.udcf.modules.multithreading.RequestsNodeService;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Runs a block multiply on one node's executor (step E10b). Production uses {@link #experimentTwo}: the
 * node's own Experiment 2 executor, as HANDOFF Section 7 asks. Tests pass a lambda.
 *
 * <p><b>Experiment 2 facts</b> ({@code RequestsNodeService.execute}): the task runs as given, with no
 * work multiplier (the FAST/MEDIUM/SLOW multiplier applies only to Experiment 2's own requests); the
 * queue is bounded and a full queue fails the future with {@link RejectedExecutionException}; a crash
 * fails it with {@link NodeDownException}. The first call starts the node's requests service, which
 * binds its requests port (720k); the matrix never stops it.</p>
 */
@FunctionalInterface
public interface NodeExecutor {

    /** Queues {@code task}; the future completes with its result or its exception. */
    CompletableFuture<MatrixMultiply.Timed> submit(Callable<MatrixMultiply.Timed> task);

    /** Node {@code node}'s Experiment 2 executor, through its requests service (started on first use). */
    static NodeExecutor experimentTwo(ClusterNode node, MultithreadingProperties properties, MeterRegistry meterRegistry,
                                      ClusterEventBus bus) {
        Objects.requireNonNull(node, "node must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        Objects.requireNonNull(bus, "bus must not be null");
        return task -> RequestsNodeService.on(node, properties, meterRegistry, bus).execute(task);
    }

    /**
     * Multiplies {@code aRows} by {@code b} on this executor and waits at most {@code timeoutMillis}
     * (queue wait included). The time is measured inside the task, around the multiply only
     * ({@link MatrixMultiply#timedRows}), so queue wait is never part of it.
     *
     * <p><b>Abandoned tasks.</b> On a timeout (or an interrupt) the task is marked abandoned and its
     * future cancelled: a task still in the queue never starts its multiply later, so it cannot steal
     * CPU from the next run. A task that has already started runs to the end (the multiply does not
     * check interrupts) and its result is discarded.</p>
     *
     * @throws MatrixComputeException EXECUTOR_UNAVAILABLE, EXECUTOR_REJECTED, COMPUTE_TIMED_OUT or
     *                                COMPUTE_FAILED; never anything else for a failure of the executor
     */
    default MatrixMultiply.Timed multiply(double[] aRows, double[] b, int n, long timeoutMillis, LongSupplier nanoClock)
            throws MatrixComputeException {
        Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("timeoutMillis must be >= 1, was " + timeoutMillis);
        }
        AtomicBoolean abandoned = new AtomicBoolean();
        CompletableFuture<MatrixMultiply.Timed> future;
        try {
            future = submit(() -> {
                if (abandoned.get()) {
                    throw new CancellationException("the caller stopped waiting before this multiply started");
                }
                return MatrixMultiply.timedRows(nanoClock, aRows, b, n);
            });
        } catch (NodeDownException e) {
            throw new MatrixComputeException(MatrixComputeException.Reason.EXECUTOR_UNAVAILABLE,
                    "The node's executor is not running: the node is down (" + e.getClass().getSimpleName() + ").");
        } catch (RuntimeException e) {
            throw new MatrixComputeException(MatrixComputeException.Reason.EXECUTOR_UNAVAILABLE,
                    "The node's executor could not be started (" + e.getClass().getSimpleName() + ").");
        }
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            abandon(abandoned, future);
            throw new MatrixComputeException(MatrixComputeException.Reason.COMPUTE_TIMED_OUT,
                    "The multiply did not finish within " + timeoutMillis + " ms, queue wait included.");
        } catch (InterruptedException e) {
            abandon(abandoned, future);
            Thread.currentThread().interrupt();
            throw new MatrixComputeException(MatrixComputeException.Reason.COMPUTE_FAILED,
                    "Interrupted while waiting for the multiply.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RejectedExecutionException) {
                throw new MatrixComputeException(MatrixComputeException.Reason.EXECUTOR_REJECTED,
                        "The node's executor queue was full, so the multiply was rejected.");
            }
            if (cause instanceof NodeDownException) {
                throw new MatrixComputeException(MatrixComputeException.Reason.EXECUTOR_UNAVAILABLE,
                        "The node went down before the multiply finished (NodeDownException).");
            }
            throw new MatrixComputeException(MatrixComputeException.Reason.COMPUTE_FAILED,
                    "The multiply failed (" + (cause == null ? "unknown" : cause.getClass().getSimpleName()) + ").");
        } catch (CancellationException e) {
            throw new MatrixComputeException(MatrixComputeException.Reason.COMPUTE_FAILED,
                    "The multiply was cancelled before it finished.");
        }
    }

    private static void abandon(AtomicBoolean abandoned, CompletableFuture<?> future) {
        abandoned.set(true);   // first: a queued task that starts after this point returns at once
        future.cancel(false);
    }
}
