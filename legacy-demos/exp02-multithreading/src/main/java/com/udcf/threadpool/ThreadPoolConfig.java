package com.udcf.threadpool;

import com.udcf.config.ThreadPoolProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Builds the executor that backs Experiment 2.
 *
 * <p>A raw {@link ThreadPoolExecutor} is exposed rather than Spring's
 * ThreadPoolTaskExecutor wrapper because the dashboard needs direct access to
 * {@code getActiveCount()}, {@code getQueue().size()} and {@code getCompletedTaskCount()}.
 * Reading those from the real executor is what keeps the metrics honest.</p>
 *
 * <p>Two deliberate choices worth noting for the report:</p>
 * <ul>
 *   <li>The queue is <b>bounded</b>. An unbounded queue would mean maxPoolSize is never
 *       reached and rejection never happens, so backpressure could not be demonstrated.</li>
 *   <li>The rejection policy is <b>AbortPolicy</b>. CallerRunsPolicy would silently run
 *       work on the HTTP thread, which would distort both throughput and the thread
 *       names shown in the UI.</li>
 * </ul>
 */
@Configuration
public class ThreadPoolConfig {

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor distributedRequestExecutor(ThreadPoolProperties properties) {
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(properties.getQueueCapacity());

        return new ThreadPoolExecutor(
                properties.getCorePoolSize(),
                properties.getMaxPoolSize(),
                properties.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                queue,
                new NamedThreadFactory(properties.getThreadNamePrefix()),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
