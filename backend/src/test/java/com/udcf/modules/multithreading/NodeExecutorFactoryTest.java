package com.udcf.modules.multithreading;

import com.udcf.core.cluster.NodeCapacity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the executor is built from configuration and, crucially, that the queue is
 * bounded and the rejection policy aborts — the two choices the experiment depends on —
 * and that each node's pool is sized by its capacity.
 */
class NodeExecutorFactoryTest {

    private static final MultithreadingProperties PROPERTIES =
            new MultithreadingProperties(200, 60, "udcf-worker-", 30, 500);

    private ThreadPoolExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private ThreadPoolExecutor configured() {
        return NodeExecutorFactory.create(3, 6, 25, 30, "cfg-worker-");
    }

    @Test
    @DisplayName("applies pool sizes and keep-alive from configuration")
    void appliesConfiguredSizes() {
        executor = configured();

        assertThat(executor.getCorePoolSize()).isEqualTo(3);
        assertThat(executor.getMaximumPoolSize()).isEqualTo(6);
        assertThat(executor.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(30);
    }

    @Test
    @DisplayName("queue is bounded so backpressure can actually occur")
    void queueIsBounded() {
        executor = configured();

        assertThat(executor.getQueue().remainingCapacity()).isEqualTo(25);
    }

    @Test
    @DisplayName("rejection aborts rather than running work on the calling thread")
    void rejectionPolicyAborts() {
        executor = configured();

        assertThat(executor.getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
    }

    @Test
    @DisplayName("uses the configured thread name prefix")
    void usesConfiguredThreadNames() throws InterruptedException {
        executor = configured();
        StringBuilder observed = new StringBuilder();

        executor.execute(() -> observed.append(Thread.currentThread().getName()));
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(observed.toString()).startsWith("cfg-worker-");
    }

    @ParameterizedTest(name = "{0} gets core = max = {1}")
    @CsvSource({"FAST, 4", "MEDIUM, 2", "SLOW, 1"})
    @DisplayName("a node's pool has core = max = its capacity's thread count")
    void sizesByCapacity(NodeCapacity capacity, int threads) {
        executor = NodeExecutorFactory.forNode(1, capacity, PROPERTIES);

        assertThat(executor.getCorePoolSize()).isEqualTo(threads);
        assertThat(executor.getMaximumPoolSize()).isEqualTo(threads);
    }

    @Test
    @DisplayName("a node's queue capacity and keep-alive come from the properties, with AbortPolicy")
    void nodeExecutorUsesProperties() {
        executor = NodeExecutorFactory.forNode(2, NodeCapacity.MEDIUM,
                new MultithreadingProperties(7, 45, "udcf-worker-", 30, 500));

        assertThat(executor.getQueue().remainingCapacity()).isEqualTo(7);
        assertThat(executor.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(45);
        assertThat(executor.getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
    }

    @Test
    @DisplayName("node k's worker threads are named udcf-worker-n<k>-<i>")
    void namesThreadsPerNode() throws InterruptedException {
        executor = NodeExecutorFactory.forNode(3, NodeCapacity.SLOW, PROPERTIES);
        StringBuilder observed = new StringBuilder();

        executor.execute(() -> observed.append(Thread.currentThread().getName()));
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(observed.toString()).isEqualTo("udcf-worker-n3-1");
    }

    @Test
    @DisplayName("a crashed node's executor stays shut down; recover gets a fresh one from forNode")
    void recoverBuildsAFreshExecutor() {
        ThreadPoolExecutor crashed = NodeExecutorFactory.forNode(1, NodeCapacity.FAST, PROPERTIES);
        crashed.shutdownNow();

        executor = NodeExecutorFactory.forNode(1, NodeCapacity.FAST, PROPERTIES);

        assertThat(crashed.isShutdown()).isTrue();
        assertThat(executor).isNotSameAs(crashed);
        assertThat(executor.isShutdown()).isFalse();
    }
}
