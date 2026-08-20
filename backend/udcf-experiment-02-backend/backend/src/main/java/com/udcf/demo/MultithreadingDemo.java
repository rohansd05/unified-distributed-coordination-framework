package com.udcf.demo;

import com.udcf.model.DistributedRequest;
import com.udcf.model.WorkloadType;
import com.udcf.threadpool.NamedThreadFactory;
import com.udcf.threadpool.WorkloadExecutor;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone console demonstration of Experiment 2 — Implementation of
 * Multithreading in a Distributed System.
 *
 * <p>This class does NOT start Spring or any HTTP server. It builds the same
 * {@link ThreadPoolExecutor}, {@link WorkloadExecutor} and {@link DistributedRequest}
 * classes used by the real UDCF node, and drives them directly, so the console
 * output can be captured as a lab screenshot without needing curl or Postman.</p>
 *
 * <p>Run it from VS Code: open this file inside the {@code backend} Maven project
 * (so the classpath resolves) and click Run above {@code main}. It can also be run
 * from a terminal:</p>
 * <pre>
 *   cd backend
 *   mvn compile exec:java -Dexec.mainClass="com.udcf.demo.MultithreadingDemo"
 * </pre>
 */
public class MultithreadingDemo {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static final AtomicInteger COMPLETED = new AtomicInteger(0);

    public static void main(String[] args) throws InterruptedException {
        printBanner();

        int nodeId = 1;
        int coreThreads = 4;
        int maxThreads = 8;
        int queueCapacity = 200;
        int totalRequests = 20;

        System.out.println("Node Identity   : NODE-" + nodeId + " (port 8081)");
        System.out.println("Core Threads    : " + coreThreads);
        System.out.println("Max Threads     : " + maxThreads);
        System.out.println("Queue Capacity  : " + queueCapacity);
        System.out.println("Requests to run : " + totalRequests);
        System.out.println();
        System.out.println("Starting distributed request threads...");
        System.out.println();

        // Exactly the same executor construction used by ThreadPoolConfig in the real node.
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                coreThreads,
                maxThreads,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new NamedThreadFactory("udcf-worker-"),
                new ThreadPoolExecutor.AbortPolicy()
        );

        WorkloadExecutor workloadExecutor = new WorkloadExecutor();

        List<WorkloadType> pattern = List.of(
                WorkloadType.CPU_HASH, WorkloadType.IO_SIMULATED, WorkloadType.MIXED
        );

        for (int i = 1; i <= totalRequests; i++) {
            WorkloadType type = pattern.get(i % pattern.size());
            int payloadSize = 20 + (i * 5);
            DistributedRequest request =
                    new DistributedRequest(String.format("req-%02d", i), nodeId, type, payloadSize);

            executor.execute(() -> runRequest(request, workloadExecutor, totalRequests));
        }

        executor.shutdown();
        boolean finishedInTime = executor.awaitTermination(30, TimeUnit.SECONDS);

        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("           MULTITHREADING DEMONSTRATION COMPLETED");
        System.out.println("=".repeat(70));
        System.out.println();
        System.out.println("Total requests submitted   : " + totalRequests);
        System.out.println("Total requests completed   : " + COMPLETED.get());
        System.out.println("Largest pool size reached  : " + executor.getLargestPoolSize());
        System.out.println("Completed task count       : " + executor.getCompletedTaskCount());
        System.out.println("Clean shutdown within 30s  : " + finishedInTime);
        System.out.println();
    }

    /** Executed on a pool worker thread — this is the concurrent part. */
    private static void runRequest(DistributedRequest request,
                                   WorkloadExecutor workloadExecutor,
                                   int totalRequests) {
        String threadName = Thread.currentThread().getName();
        request.markStarted(threadName);

        log(threadName, "Started " + request.getId()
                + " [" + request.getType() + "] payloadSize=" + request.getPayloadSize());

        try {
            String summary = workloadExecutor.execute(request.getType(), request.getPayloadSize());
            request.markCompleted(summary);

            log(threadName, "Completed " + request.getId()
                    + " -> " + summary
                    + " (queueWait=" + round(request.queueWaitMillis()) + "ms"
                    + ", processing=" + round(request.processingMillis()) + "ms"
                    + ", total=" + round(request.totalMillis()) + "ms)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            request.markFailed("Interrupted");
            log(threadName, "Interrupted while processing " + request.getId());
        }

        int done = COMPLETED.incrementAndGet();
        if (done == totalRequests) {
            System.out.println();
            System.out.println("[" + timestamp() + "] All " + totalRequests
                    + " distributed requests have completed.");
        }
    }

    private static void log(String threadName, String message) {
        System.out.println("[" + timestamp() + "] [" + threadName + "] " + message);
    }

    private static String timestamp() {
        return LocalTime.now().format(TIME_FORMAT);
    }

    private static double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private static void printBanner() {
        System.out.println("=".repeat(70));
        System.out.println("        UNIFIED DISTRIBUTED COORDINATION FRAMEWORK");
        System.out.println("                    EXPERIMENT 2");
        System.out.println("         MULTITHREADING DEMONSTRATION (standalone)");
        System.out.println("=".repeat(70));
        System.out.println();
    }
}
