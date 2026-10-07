package com.udcf.config;

import com.udcf.modules.multithreading.LoggingRequestEventPublisher;
import com.udcf.modules.multithreading.MultithreadingProperties;
import com.udcf.modules.multithreading.NodeExecutorFactory;
import com.udcf.modules.multithreading.RequestEventPublisher;
import com.udcf.modules.multithreading.RequestProcessingService;
import com.udcf.modules.multithreading.RequestRegistry;
import com.udcf.modules.multithreading.ThreadPoolMetrics;
import com.udcf.modules.multithreading.ThreadPoolStatsService;
import com.udcf.modules.multithreading.ThroughputTracker;
import com.udcf.modules.multithreading.WorkloadExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Temporary wiring that keeps the old {@code /api/multithreading} endpoints working while
 * the Experiment 2 engine lives in {@code com.udcf.modules.multithreading} as plain classes.
 *
 * <p>It builds one engine for node {@code udcf.node.id}, sized from {@code udcf.threadpool}
 * with work multiplier 1, so the old endpoints behave exactly as before Step E2a.</p>
 *
 * <p>Retired in E2c, together with MultithreadingController, ThreadPoolProperties,
 * {@code udcf.threadpool} and {@code udcf.node.id}. While it exists, nothing else may bind
 * Experiment 2 gauges for the same node id: Micrometer would return these gauges, still
 * bound to this executor.</p>
 *
 * <p>Covered by UdcfBackendApplicationTests and PrometheusScrapeTest.</p>
 */
// Retired in E2c.
@Configuration
public class LegacyMultithreadingConfig {

    private final int nodeId;

    public LegacyMultithreadingConfig(@Value("${udcf.node.id}") int nodeId) {
        this.nodeId = nodeId;
    }

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor distributedRequestExecutor(ThreadPoolProperties properties) {
        return NodeExecutorFactory.create(properties.getCorePoolSize(), properties.getMaxPoolSize(),
                properties.getQueueCapacity(), properties.getKeepAliveSeconds(),
                properties.getThreadNamePrefix());
    }

    @Bean
    public WorkloadExecutor workloadExecutor() {
        return new WorkloadExecutor();
    }

    @Bean
    public RequestRegistry requestRegistry(MultithreadingProperties properties) {
        return new RequestRegistry(properties.requestHistorySize());
    }

    @Bean
    public ThroughputTracker throughputTracker(ThreadPoolProperties properties) {
        return new ThroughputTracker(properties.getMetricsWindowSeconds());
    }

    @Bean
    public ThreadPoolMetrics threadPoolMetrics(MeterRegistry meterRegistry, ThreadPoolExecutor executor,
                                               ThroughputTracker throughputTracker) {
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(meterRegistry, executor, throughputTracker, nodeId);
        metrics.bindGauges();
        return metrics;
    }

    @Bean
    public RequestEventPublisher requestEventPublisher() {
        return new LoggingRequestEventPublisher();
    }

    @Bean
    public RequestProcessingService requestProcessingService(ThreadPoolExecutor executor,
                                                             WorkloadExecutor workloadExecutor,
                                                             RequestRegistry registry,
                                                             ThroughputTracker throughputTracker,
                                                             ThreadPoolMetrics metrics,
                                                             RequestEventPublisher events) {
        return new RequestProcessingService(executor, workloadExecutor, registry, throughputTracker,
                metrics, events, nodeId, 1);
    }

    @Bean
    public ThreadPoolStatsService threadPoolStatsService(ThreadPoolExecutor executor,
                                                         RequestRegistry registry,
                                                         ThroughputTracker throughputTracker,
                                                         ThreadPoolProperties properties) {
        return new ThreadPoolStatsService(executor, registry, throughputTracker,
                properties.getQueueCapacity(), nodeId);
    }
}
