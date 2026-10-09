package com.udcf.modules.mapreduce;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JobReportTest {

    @Test
    void testTimingFieldsNullBeforeMeasured() {
        JobReport report = new JobReport("word-count");
        assertEquals("word-count", report.jobName());
        assertNull(report.mapMillis(), "mapMillis must be null before measured (Rule R7)");
        assertNull(report.shuffleMillis(), "shuffleMillis must be null before measured (Rule R7)");
        assertNull(report.reduceMillis(), "reduceMillis must be null before measured (Rule R7)");
        assertNull(report.totalMillis(), "totalMillis must be null before measured (Rule R7)");
    }

    @Test
    void testCombinerSavingPercentage() {
        JobReport report = new JobReport("test-job");
        assertNull(report.combinerSavingPercent());

        report.setPairsEmitted(100);
        report.setPairsAfterCombine(25);
        assertEquals(75.0, report.combinerSavingPercent());

        report.setPairsEmitted(100);
        report.setPairsAfterCombine(100);
        assertEquals(0.0, report.combinerSavingPercent());
    }

    @Test
    void testCombinerSavingPercentNullWhenNoPairsEmitted() {
        JobReport report = new JobReport("test-job");
        assertNull(report.combinerSavingPercent(),
                "combinerSavingPercent must be null when pairsEmitted is 0 (Rule R7)");

        report.setPairsEmitted(0);
        assertNull(report.combinerSavingPercent(),
                "combinerSavingPercent must remain null when pairsEmitted is explicitly set to 0");
    }

    @Test
    void testConcurrentCounterUpdates() throws InterruptedException {
        JobReport report = new JobReport("concurrent-job");
        int threadCount = 10;
        int incrementsPerThread = 100;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int nodeId = (i % 3) + 1;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < incrementsPerThread; j++) {
                        report.countMapTask(nodeId);
                        report.countReduceTask(nodeId);
                        report.addRetry();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertEquals(threadCount * incrementsPerThread, report.failedTasksRetried());

        int totalMapTasks = report.mapTasksPerNode().values().stream().mapToInt(Integer::intValue).sum();
        int totalReduceTasks = report.reduceTasksPerNode().values().stream().mapToInt(Integer::intValue).sum();

        assertEquals(threadCount * incrementsPerThread, totalMapTasks);
        assertEquals(threadCount * incrementsPerThread, totalReduceTasks);
    }
}
