package com.udcf.threadpool;

import com.udcf.config.ThreadPoolProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the executor is wired from configuration and, crucially, that the queue is
 * bounded and the rejection policy aborts — the two choices the experiment depends on.
 */
class ThreadPoolConfigTest {

    private ThreadPoolExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private ThreadPoolProperties properties() {
        ThreadPoolProperties properties = new ThreadPoolProperties();
        properties.setCorePoolSize(3);
        properties.setMaxPoolSize(6);
        properties.setQueueCapacity(25);
        properties.setKeepAliveSeconds(30);
        properties.setThreadNamePrefix("cfg-worker-");
        return properties;
    }

    @Test
    @DisplayName("applies pool sizes and keep-alive from configuration")
    void appliesConfiguredSizes() {
        executor = new ThreadPoolConfig().distributedRequestExecutor(properties());

        assertThat(executor.getCorePoolSize()).isEqualTo(3);
        assertThat(executor.getMaximumPoolSize()).isEqualTo(6);
        assertThat(executor.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(30);
    }

    @Test
    @DisplayName("queue is bounded so backpressure can actually occur")
    void queueIsBounded() {
        executor = new ThreadPoolConfig().distributedRequestExecutor(properties());

        assertThat(executor.getQueue().remainingCapacity()).isEqualTo(25);
    }

    @Test
    @DisplayName("rejection aborts rather than running work on the calling thread")
    void rejectionPolicyAborts() {
        executor = new ThreadPoolConfig().distributedRequestExecutor(properties());

        assertThat(executor.getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
    }

    @Test
    @DisplayName("uses the configured thread name prefix")
    void usesConfiguredThreadNames() throws InterruptedException {
        executor = new ThreadPoolConfig().distributedRequestExecutor(properties());
        StringBuilder observed = new StringBuilder();

        executor.execute(() -> observed.append(Thread.currentThread().getName()));
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(observed.toString()).startsWith("cfg-worker-");
    }
}
