package com.udcf.modules.mapreduce;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapReducePipelineTest {

    @Test
    void testWordCountPipeline() throws IOException {
        List<String> input = List.of(
                "apple orange banana",
                "apple banana",
                "orange orange apple"
        );
        JobReport report = new JobReport("word-count");
        Map<String, String> results = MapReducePipeline.runLocal(new WordCountJob(), input, 3, report);

        assertEquals("3", results.get("apple"));
        assertEquals("3", results.get("orange"));
        assertEquals("2", results.get("banana"));

        assertEquals(3, report.inputLines());
        assertEquals(3, report.mapTasks());
        assertNotNull(report.mapMillis());
        assertNotNull(report.shuffleMillis());
        assertNotNull(report.reduceMillis());
        assertNotNull(report.totalMillis());
    }

    @Test
    void testEventCategoryPipeline() throws IOException {
        List<String> input = List.of(
                "2026-09-18 14:33:28.151 | node=2 | category=RECV | latency=10.48 | msg",
                "2026-09-18 14:33:28.212 | node=1 | category=RECV | latency=3.92 | msg",
                "2026-09-18 14:33:28.282 | node=2 | category=DISPATCH | latency=12.35 | msg",
                "2026-09-18 14:33:28.469 | node=2 | category=SEND | latency=2.97 | msg"
        );
        JobReport report = new JobReport("event-category-count");
        Map<String, String> results = MapReducePipeline.runLocal(new EventCategoryJob(), input, 2, report);

        assertEquals("2", results.get("RECV"));
        assertEquals("1", results.get("DISPATCH"));
        assertEquals("1", results.get("SEND"));
    }

    @Test
    void testLatencyPerNodePipeline() throws IOException {
        List<String> input = List.of(
                "node=1 | category=RECV | latency=10.00",
                "node=1 | category=RECV | latency=20.00",
                "node=2 | category=SEND | latency=30.00"
        );
        LatencyPerNodeJob job = new LatencyPerNodeJob();
        JobReport report = new JobReport("avg-latency-per-node");
        Map<String, String> results = MapReducePipeline.runLocal(job, input, 2, report);

        assertEquals("30.0;2", results.get("node-1"));
        assertEquals("30.0;1", results.get("node-2"));

        assertEquals("15.00 ms average over 2 events", job.formatResult("node-1", results.get("node-1")));
        assertEquals("30.00 ms average over 1 events", job.formatResult("node-2", results.get("node-2")));
    }

    @Test
    void testSampleTextClasspathResourceWithLfAndCrlf() throws IOException {
        List<String> rawLines = loadClasspathLines("/mapreduce/sample-text.txt");
        assertFalse(rawLines.isEmpty(), "sample-text.txt must have lines");

        String fullText = String.join("\n", rawLines);
        List<String> lfLines = List.of(fullText.split("\n"));
        String crlfText = String.join("\r\n", rawLines);
        List<String> crlfLines = List.of(crlfText.split("\r\n"));

        JobReport reportLf = new JobReport("word-count");
        Map<String, String> resultsLf = MapReducePipeline.runLocal(new WordCountJob(), lfLines, 3, reportLf);

        JobReport reportCrlf = new JobReport("word-count");
        Map<String, String> resultsCrlf = MapReducePipeline.runLocal(new WordCountJob(), crlfLines, 3, reportCrlf);

        assertEquals(resultsLf, resultsCrlf, "Results must be identical for LF and CRLF inputs");
        assertTrue(Integer.parseInt(resultsLf.get("network")) > 0);
    }

    @Test
    void testFrameworkEventsClasspathResourceWithLfAndCrlf() throws IOException {
        List<String> rawLines = loadClasspathLines("/mapreduce/framework-events.log");
        assertFalse(rawLines.isEmpty(), "framework-events.log must have lines");

        String fullText = String.join("\n", rawLines);
        List<String> lfLines = List.of(fullText.split("\n"));
        String crlfText = String.join("\r\n", rawLines);
        List<String> crlfLines = List.of(crlfText.split("\r\n"));

        JobReport reportLf = new JobReport("event-category-count");
        Map<String, String> resultsLf = MapReducePipeline.runLocal(new EventCategoryJob(), lfLines, 3, reportLf);

        JobReport reportCrlf = new JobReport("event-category-count");
        Map<String, String> resultsCrlf = MapReducePipeline.runLocal(new EventCategoryJob(), crlfLines, 3, reportCrlf);

        assertEquals(resultsLf, resultsCrlf);
        assertTrue(resultsLf.containsKey("RECV"));
        assertTrue(resultsLf.containsKey("SEND"));
    }

    @Test
    void testTaskTimeoutEnforcedAndRetriedOnNextWorker() throws IOException {
        // Condition 6: Transport blocks on latch until interrupted, proving timeout and retry without sleep
        CountDownLatch worker1Started = new CountDownLatch(1);
        CountDownLatch worker1Interrupted = new CountDownLatch(1);

        JobRegistry registry = JobRegistry.standard();
        TaskTransport underlying = TaskTransport.inMemory(registry);

        TaskTransport timeoutTransport = (targetNodeId, taskType, jobName, payload) -> {
            if (targetNodeId == 1 && taskType == TaskType.MAP) {
                worker1Started.countDown();
                try {
                    // Block indefinitely until interrupted by pipeline future.cancel(true)
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    worker1Interrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new IOException("Worker 1 task interrupted on timeout", e);
                }
            }
            return underlying.executeTask(targetNodeId, taskType, jobName, payload);
        };

        // Short timeout of 80 milliseconds
        MapReducePipeline pipeline = new MapReducePipeline(
                List.of(1, 2), timeoutTransport, Duration.ofMillis(80));

        JobReport report = new JobReport("word-count");
        try {
            Map<String, String> results = pipeline.run(
                    new WordCountJob(), List.of("hello world", "test token"), report);
            assertEquals("1", results.get("hello"));
            assertEquals("1", results.get("world"));
            assertTrue(report.failedTasksRetried() >= 1, "Timed out task must be retried");
            assertTrue(worker1Interrupted.getCount() == 0, "Worker 1 thread must have been interrupted");
        } finally {
            pipeline.shutdown();
        }
    }

    @Test
    void testEmptySplitsSkipped() throws IOException {
        // Condition 9: Empty splits are skipped, never dispatched, never counted as map tasks
        List<String> input = List.of("line1", "line2");
        JobReport report = new JobReport("word-count");

        JobRegistry registry = JobRegistry.standard();
        TaskTransport transport = TaskTransport.inMemory(registry);

        MapReducePipeline pipeline = new MapReducePipeline(List.of(1, 2, 3, 4, 5), transport);
        try {
            Map<String, String> results = pipeline.run(new WordCountJob(), input, report);
            assertEquals(2, results.size());
            assertEquals(2, report.splits());
            assertEquals(2, report.mapTasks(), "Map tasks count must only count non-empty splits");
        } finally {
            pipeline.shutdown();
        }
    }

    @Test
    void testIdenticalResultsAcrossWorkerCounts() throws IOException {
        // Condition 10a: Result identical with 1, 2, 3 and 5 workers
        List<String> input = loadClasspathLines("/mapreduce/sample-text.txt");
        WordCountJob job = new WordCountJob();

        Map<String, String> res1 = MapReducePipeline.runLocal(job, input, 1, new JobReport("wc"));
        Map<String, String> res2 = MapReducePipeline.runLocal(job, input, 2, new JobReport("wc"));
        Map<String, String> res3 = MapReducePipeline.runLocal(job, input, 3, new JobReport("wc"));
        Map<String, String> res5 = MapReducePipeline.runLocal(job, input, 5, new JobReport("wc"));

        assertEquals(res1, res2);
        assertEquals(res2, res3);
        assertEquals(res3, res5);
    }

    @Test
    void testIdenticalResultsWithAndWithoutCombinerAllThreeJobs() throws IOException {
        // Condition 10b: Identical with and without combiner for all three jobs
        List<String> textLines = List.of(
                "apple orange banana apple",
                "banana orange apple",
                "orange banana"
        );
        List<String> logLines = List.of(
                "node=1 | category=RECV | latency=10.00",
                "node=2 | category=RECV | latency=20.00",
                "node=1 | category=SEND | latency=30.00",
                "node=2 | category=SEND | latency=40.00"
        );

        // 1. WordCountJob
        WordCountJob wc = new WordCountJob();
        MapReduceJob wcNoCombiner = wrapNoCombiner(wc);
        JobReport repWcWith = new JobReport("wc");
        JobReport repWcWithout = new JobReport("wc");
        Map<String, String> wcWith = MapReducePipeline.runLocal(wc, textLines, 3, repWcWith);
        Map<String, String> wcWithout = MapReducePipeline.runLocal(wcNoCombiner, textLines, 3, repWcWithout);
        assertEquals(wcWith, wcWithout);
        assertTrue(repWcWith.combinerSavingPercent() > 0.0);
        assertEquals(0.0, repWcWithout.combinerSavingPercent());

        // 2. EventCategoryJob
        EventCategoryJob ec = new EventCategoryJob();
        MapReduceJob ecNoCombiner = wrapNoCombiner(ec);
        JobReport repEcWith = new JobReport("ec");
        JobReport repEcWithout = new JobReport("ec");
        Map<String, String> ecWith = MapReducePipeline.runLocal(ec, logLines, 3, repEcWith);
        Map<String, String> ecWithout = MapReducePipeline.runLocal(ecNoCombiner, logLines, 3, repEcWithout);
        assertEquals(ecWith, ecWithout);
        assertTrue(repEcWith.combinerSavingPercent() > 0.0);
        assertEquals(0.0, repEcWithout.combinerSavingPercent());

        // 3. LatencyPerNodeJob
        List<String> latencyLines = List.of(
                "node=1 | category=RECV | latency=10.00",
                "node=1 | category=RECV | latency=20.00",
                "node=2 | category=SEND | latency=30.00",
                "node=2 | category=SEND | latency=40.00"
        );
        LatencyPerNodeJob lp = new LatencyPerNodeJob();
        MapReduceJob lpNoCombiner = wrapNoCombiner(lp);
        JobReport repLpWith = new JobReport("lp");
        JobReport repLpWithout = new JobReport("lp");
        Map<String, String> lpWith = MapReducePipeline.runLocal(lp, latencyLines, 3, repLpWith);
        Map<String, String> lpWithout = MapReducePipeline.runLocal(lpNoCombiner, latencyLines, 3, repLpWithout);
        assertEquals(lpWith, lpWithout);
        assertTrue(repLpWith.combinerSavingPercent() > 0.0);
        assertEquals(0.0, repLpWithout.combinerSavingPercent());
    }

    @Test
    void testResultMapSortedDeterministically() throws IOException {
        // Condition 10c: Result map is sorted by key
        List<String> input = List.of("zebra apple mango cherry banana");
        Map<String, String> results = MapReducePipeline.runLocal(
                new WordCountJob(), input, 3, new JobReport("wc"));

        List<String> keys = new ArrayList<>(results.keySet());
        List<String> sortedKeys = new ArrayList<>(keys);
        Collections.sort(sortedKeys);

        assertEquals(sortedKeys, keys, "Results must be sorted by key in ascending order");
        assertEquals("apple", keys.get(0));
        assertEquals("zebra", keys.get(keys.size() - 1));
    }

    @Test
    void testTaskRetriedAfterFailureProducesSameResultAndIncrementsCounter() throws IOException {
        // Condition 10d: A task retried after failure produces same result and increments retry counter
        Set<Integer> failedNodes = ConcurrentHashMap.newKeySet();

        JobRegistry registry = JobRegistry.standard();
        TaskTransport inMem = TaskTransport.inMemory(registry);

        TaskTransport flakyTransport = (targetNodeId, taskType, jobName, payload) -> {
            if (targetNodeId == 1 && failedNodes.add(1)) {
                throw new IOException("Simulated node 1 crash during " + taskType);
            }
            return inMem.executeTask(targetNodeId, taskType, jobName, payload);
        };

        MapReducePipeline pipeline = new MapReducePipeline(List.of(1, 2, 3), flakyTransport);
        JobReport report = new JobReport("word-count");
        try {
            Map<String, String> results = pipeline.run(
                    new WordCountJob(), List.of("a b c d e f"), report);

            assertEquals(1, report.failedTasksRetried());
            assertEquals("1", results.get("a"));
            assertEquals("1", results.get("f"));
        } finally {
            pipeline.shutdown();
        }
    }

    @Test
    void testAllWorkersFailingThrowsIOException() {
        // Condition 10e: With every worker failing, pipeline throws clear IOException
        TaskTransport brokenTransport = (targetNodeId, taskType, jobName, payload) -> {
            throw new IOException("Target worker " + targetNodeId + " is completely dead");
        };

        MapReducePipeline pipeline = new MapReducePipeline(List.of(1, 2), brokenTransport);
        JobReport report = new JobReport("word-count");
        try {
            assertThrows(IOException.class, () -> pipeline.run(
                    new WordCountJob(), List.of("some line"), report));
        } finally {
            pipeline.shutdown();
        }
    }

    @Test
    void testNoUdcfMapreduceThreadsAliveAfterShutdown() throws IOException {
        // Condition 11: Name threads udcf-mapreduce-*, bounded shutdown, no thread alive after shutdown
        JobRegistry registry = JobRegistry.standard();
        TaskTransport transport = TaskTransport.inMemory(registry);
        MapReducePipeline pipeline = new MapReducePipeline(List.of(1, 2, 3), transport);

        pipeline.run(new WordCountJob(), List.of("thread check test"), new JobReport("wc"));
        pipeline.shutdown();

        // Check active threads in JVM
        Thread[] threads = new Thread[Thread.activeCount() + 50];
        int count = Thread.enumerate(threads);
        for (int i = 0; i < count; i++) {
            Thread t = threads[i];
            if (t != null && t.isAlive() && t.getName().startsWith("udcf-mapreduce-")) {
                assertFalse(t.isAlive(), "Thread " + t.getName() + " should not be alive after shutdown");
            }
        }
    }

    @Test
    void testEmptyInputAndOnlyEmptyLinesHonesty() throws IOException {
        WordCountJob job = new WordCountJob();

        // 1. Completely empty input
        JobReport emptyReport = new JobReport("word-count");
        Map<String, String> emptyResults = MapReducePipeline.runLocal(job, List.of(), 3, emptyReport);
        assertTrue(emptyResults.isEmpty());
        assertEquals(0, emptyReport.inputLines());
        assertEquals(0, emptyReport.mapTasks());
        assertEquals(0, emptyReport.reduceTasks());
        assertNull(emptyReport.mapMillis(), "mapMillis must be null when map stage did not run");
        assertNull(emptyReport.shuffleMillis(), "shuffleMillis must be null when shuffle stage did not run");
        assertNull(emptyReport.reduceMillis(), "reduceMillis must be null when reduce stage did not run");
        assertNotNull(emptyReport.totalMillis(), "totalMillis is measured because pipeline call itself executed");
        assertNull(emptyReport.combinerSavingPercent(), "combinerSavingPercent must be null when pairsEmitted is 0");

        // 2. Only-empty lines
        JobReport blankReport = new JobReport("word-count");
        Map<String, String> blankResults = MapReducePipeline.runLocal(job, List.of("", "   ", ""), 3, blankReport);
        assertTrue(blankResults.isEmpty());
        assertEquals(3, blankReport.inputLines());
        assertEquals(0, blankReport.mapTasks());
        assertEquals(0, blankReport.reduceTasks());
        assertNull(blankReport.mapMillis(), "mapMillis must be null when map stage did not run");
        assertNull(blankReport.shuffleMillis(), "shuffleMillis must be null when shuffle stage did not run");
        assertNull(blankReport.reduceMillis(), "reduceMillis must be null when reduce stage did not run");
        assertNotNull(blankReport.totalMillis(), "totalMillis is measured because pipeline call itself executed");
        assertNull(blankReport.combinerSavingPercent(), "combinerSavingPercent must be null when pairsEmitted is 0");
    }

    private static List<String> loadClasspathLines(String resourcePath) throws IOException {
        try (InputStream in = MapReducePipelineTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(in, "Resource not found on classpath: " + resourcePath);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().toList();
            }
        }
    }

    private static MapReduceJob wrapNoCombiner(MapReduceJob delegate) {
        return new MapReduceJob() {
            @Override public String name() { return delegate.name(); }
            @Override public String description() { return delegate.description(); }
            @Override public void map(String line, BiConsumer<String, String> emit) { delegate.map(line, emit); }
            @Override public String combine(String key, List<String> values) { return delegate.combine(key, values); }
            @Override public String reduce(String key, List<String> values) { return delegate.reduce(key, values); }
            @Override public boolean usesCombiner() { return false; }
            @Override public String formatResult(String key, String value) { return delegate.formatResult(key, value); }
        };
    }

    // ---------------------------------------------------------------- E7c: TaskAttemptListener (add-only)

    /** One listener call, as recorded by the tests below. */
    private record Attempt(TaskType type, int taskIndex, int attempt, int nodeId, String failureReason) {
    }

    @Test
    void testListenerSeesOneSuccessfulFirstAttemptPerTask() throws IOException {
        List<Attempt> attempts = Collections.synchronizedList(new ArrayList<>());
        MapReducePipeline pipeline = new MapReducePipeline(List.of(1, 2, 3),
                TaskTransport.inMemory(JobRegistry.standard()));
        JobReport report = new JobReport("word-count");
        try {
            pipeline.run(new WordCountJob(), List.of("a b", "c d", "e f"), report,
                    (type, taskIndex, attempt, nodeId, failureReason) ->
                            attempts.add(new Attempt(type, taskIndex, attempt, nodeId, failureReason)));
        } finally {
            pipeline.shutdown();
        }

        List<Attempt> maps = attempts.stream().filter(a -> a.type() == TaskType.MAP)
                .sorted(java.util.Comparator.comparingInt(Attempt::taskIndex)).toList();
        assertEquals(List.of(new Attempt(TaskType.MAP, 0, 1, 1, null), new Attempt(TaskType.MAP, 1, 1, 2, null),
                new Attempt(TaskType.MAP, 2, 1, 3, null)), maps);
        long reduces = attempts.stream().filter(a -> a.type() == TaskType.REDUCE).count();
        assertEquals(report.reduceTasks(), reduces);
        assertTrue(attempts.stream().allMatch(a -> a.attempt() == 1 && a.failureReason() == null));
    }

    @Test
    void testListenerSeesFailedAttemptThenRetryOnNextWorker() throws IOException {
        Set<Integer> failedOnce = ConcurrentHashMap.newKeySet();
        TaskTransport inMem = TaskTransport.inMemory(JobRegistry.standard());
        TaskTransport flaky = (targetNodeId, taskType, jobName, payload) -> {
            if (targetNodeId == 2 && taskType == TaskType.MAP && failedOnce.add(2)) {
                throw new IOException("Connection refused by node 2");
            }
            return inMem.executeTask(targetNodeId, taskType, jobName, payload);
        };
        List<Attempt> attempts = Collections.synchronizedList(new ArrayList<>());
        MapReducePipeline pipeline = new MapReducePipeline(List.of(1, 2, 3), flaky);
        try {
            pipeline.run(new WordCountJob(), List.of("a", "b", "c"), new JobReport("word-count"),
                    (type, taskIndex, attempt, nodeId, failureReason) ->
                            attempts.add(new Attempt(type, taskIndex, attempt, nodeId, failureReason)));
        } finally {
            pipeline.shutdown();
        }

        List<Attempt> task1 = attempts.stream().filter(a -> a.type() == TaskType.MAP && a.taskIndex() == 1).toList();
        assertEquals(List.of(new Attempt(TaskType.MAP, 1, 1, 2, "Connection refused by node 2"),
                new Attempt(TaskType.MAP, 1, 2, 3, null)), task1);
    }

    @Test
    void testListenerExceptionNeverChangesTheOutcome() throws IOException {
        List<String> input = List.of("apple orange", "apple banana", "orange apple");
        Map<String, String> expected = MapReducePipeline.runLocal(new WordCountJob(), input, 3, new JobReport("word-count"));

        MapReducePipeline pipeline = new MapReducePipeline(List.of(1, 2, 3),
                TaskTransport.inMemory(JobRegistry.standard()));
        JobReport report = new JobReport("word-count");
        Map<String, String> results;
        try {
            results = pipeline.run(new WordCountJob(), input, report,
                    (type, taskIndex, attempt, nodeId, failureReason) -> {
                        throw new IllegalStateException("listener bug");
                    });
        } finally {
            pipeline.shutdown();
        }

        assertEquals(expected, results);
        assertEquals(0, report.failedTasksRetried());
    }

    @Test
    void testListenerMustNotBeNull() {
        MapReducePipeline pipeline = new MapReducePipeline(List.of(1), TaskTransport.inMemory(JobRegistry.standard()));
        try {
            assertThrows(NullPointerException.class, () -> pipeline.run(new WordCountJob(), List.of("a"),
                    new JobReport("word-count"), null));
        } finally {
            pipeline.shutdown();
        }
    }
}
