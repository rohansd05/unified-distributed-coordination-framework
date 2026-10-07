package com.udcf.modules.multithreading;

import com.udcf.core.cluster.NodeCapacity;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Builds the executor that backs Experiment 2 on one node.
 *
 * <p>A raw {@link ThreadPoolExecutor} is returned rather than Spring's
 * ThreadPoolTaskExecutor wrapper because the page needs direct access to
 * {@code getActiveCount()}, {@code getQueue().size()} and {@code getCompletedTaskCount()}.
 * Reading those from the real executor is what keeps the metrics honest.</p>
 *
 * <p>Two deliberate choices worth noting for the report:</p>
 * <ul>
 *   <li>The queue is <b>bounded</b>. An unbounded queue would mean the pool never rejects,
 *       so backpressure could not be demonstrated.</li>
 *   <li>The rejection policy is <b>AbortPolicy</b>. CallerRunsPolicy would silently run
 *       work on the submitting thread, which would distort both throughput and the thread
 *       names shown in the UI.</li>
 * </ul>
 *
 * <p><b>Per-node sizing.</b> {@link #forNode} gives node k core = max =
 * {@link NodeCapacity#threads()} (FAST 4, MEDIUM 2, SLOW 1), so the capacity difference
 * Experiment 6 relies on is the real thread count, as in the legacy Exp 6 worker.</p>
 *
 * <p><b>Lifecycle.</b> A {@link ThreadPoolExecutor} cannot be restarted once shut down.
 * A node crash therefore shuts its executor down, and a recover calls {@link #forNode}
 * again for a fresh one; every class that holds the executor is rebuilt with it.</p>
 */
public final class NodeExecutorFactory {

    private NodeExecutorFactory() {
    }

    /** Node {@code nodeId}'s executor, sized by its capacity; threads named {@code <prefix>n<k>-<i>}. */
    public static ThreadPoolExecutor forNode(int nodeId, NodeCapacity capacity, MultithreadingProperties properties) {
        return create(capacity.threads(), capacity.threads(), properties.queueCapacity(),
                properties.keepAliveSeconds(), properties.threadNamePrefix() + "n" + nodeId + "-");
    }

    /** A bounded, aborting executor with explicit sizes. */
    public static ThreadPoolExecutor create(int corePoolSize, int maxPoolSize, int queueCapacity,
                                            long keepAliveSeconds, String threadNamePrefix) {
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(queueCapacity);

        return new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                queue,
                new NamedThreadFactory(threadNamePrefix),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
