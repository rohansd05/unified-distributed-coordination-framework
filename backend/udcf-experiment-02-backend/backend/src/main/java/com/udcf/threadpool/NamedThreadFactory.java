package com.udcf.threadpool;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Produces predictably named, non-daemon worker threads.
 *
 * <p>Naming matters here beyond tidiness: the dashboard shows which thread handled each
 * request, and that is the clearest evidence that requests really are being processed
 * concurrently rather than serially.</p>
 */
public class NamedThreadFactory implements ThreadFactory {

    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger(1);

    public NamedThreadFactory(String prefix) {
        this.prefix = prefix == null ? "udcf-worker-" : prefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
        // Non-daemon so in-flight requests are not silently dropped on shutdown.
        thread.setDaemon(false);
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    }

    public String getPrefix() {
        return prefix;
    }
}
