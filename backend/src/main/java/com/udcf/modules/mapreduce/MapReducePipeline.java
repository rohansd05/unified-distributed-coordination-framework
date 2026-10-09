package com.udcf.modules.mapreduce;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure MapReduce pipeline coordinator orchestrating split -> map -> combine -> shuffle
 * -> partition -> reduce -> collect with task retry and timeout enforcement.
 *
 * <p>Free of sockets, Spring dependencies, and hard-coded ports. Worker communication
 * is mediated solely through {@link TaskTransport}.</p>
 */
public class MapReducePipeline {

    /** Default per-task timeout from HANDOFF Appendix B (15 seconds). */
    public static final Duration DEFAULT_TASK_TIMEOUT = Duration.ofSeconds(15);

    private static final AtomicInteger POOL_THREAD_SEQ = new AtomicInteger(0);

    private final List<Integer> workerIds;
    private final TaskTransport transport;
    private final Duration taskTimeout;
    private final int reducers;
    private final ExecutorService taskPool;
    private final boolean ownsExecutor;

    public MapReducePipeline(List<Integer> workerIds, TaskTransport transport) {
        this(workerIds, transport, DEFAULT_TASK_TIMEOUT);
    }

    public MapReducePipeline(List<Integer> workerIds, TaskTransport transport, Duration taskTimeout) {
        this(workerIds, transport, taskTimeout, workerIds != null ? workerIds.size() : 1);
    }

    public MapReducePipeline(List<Integer> workerIds, TaskTransport transport,
                              Duration taskTimeout, int reducers) {
        this(workerIds, transport, taskTimeout, reducers, createDefaultPool(workerIds), true);
    }

    public MapReducePipeline(List<Integer> workerIds, TaskTransport transport,
                              Duration taskTimeout, int reducers,
                              ExecutorService taskPool) {
        this(workerIds, transport, taskTimeout, reducers, taskPool, false);
    }

    private MapReducePipeline(List<Integer> workerIds, TaskTransport transport,
                              Duration taskTimeout, int reducers,
                              ExecutorService taskPool, boolean ownsExecutor) {
        Objects.requireNonNull(workerIds, "workerIds must not be null");
        if (workerIds.isEmpty()) {
            throw new IllegalArgumentException("workerIds must not be empty");
        }
        if (reducers <= 0) {
            throw new IllegalArgumentException("reducers must be positive: " + reducers);
        }
        this.workerIds = List.copyOf(workerIds);
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.taskTimeout = Objects.requireNonNull(taskTimeout, "taskTimeout must not be null");
        this.reducers = reducers;
        this.taskPool = Objects.requireNonNull(taskPool, "taskPool must not be null");
        this.ownsExecutor = ownsExecutor;
    }

    private static ExecutorService createDefaultPool(List<Integer> workerIds) {
        int poolSize = Math.max(2, workerIds != null ? workerIds.size() : 2);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "udcf-mapreduce-" + POOL_THREAD_SEQ.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return Executors.newCachedThreadPool(factory);
    }

    /**
     * Executes the complete MapReduce pipeline across the configured workers.
     *
     * <p>When {@code input} is empty or contains only empty lines, no mapper, shuffle,
     * or reducer stages run. In accordance with Rule R7 (honesty), the stage timings
     * ({@code mapMillis}, {@code shuffleMillis}, {@code reduceMillis}) remain {@code null},
     * while {@code totalMillis} is measured to record the wall-clock execution time of the
     * pipeline invocation itself.</p>
     *
     * @return Deterministic map of results sorted by key.
     * @throws IOException if all worker retries fail for any task.
     */
    public Map<String, String> run(MapReduceJob job, List<String> input, JobReport report)
            throws IOException {
        Objects.requireNonNull(job, "job must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(report, "report must not be null");

        long jobStart = System.nanoTime();
        report.setInputLines(input.size());

        // ---------------------------------------------------------------- 1. SPLIT
        List<List<String>> allSplits = InputSplitter.split(input, workerIds.size());
        List<List<String>> activeSplits = new ArrayList<>();
        for (List<String> s : allSplits) {
            boolean hasContent = s.stream().anyMatch(line -> line != null && !line.trim().isEmpty());
            if (hasContent) {
                activeSplits.add(s);
            }
        }
        report.setSplits(activeSplits.size());
        report.setMapTasks(activeSplits.size());

        if (activeSplits.isEmpty()) {
            report.setPairsEmitted(0);
            report.setPairsAfterCombine(0);
            report.setShuffleKeys(0);
            report.setReduceTasks(0);
            report.setTotalMillis((System.nanoTime() - jobStart) / 1_000_000.0);
            return new TreeMap<>();
        }

        // ---------------------------------------------------------------- 2. MAP
        long mapStart = System.nanoTime();
        List<Future<String>> mapFutures = new ArrayList<>();
        for (int i = 0; i < activeSplits.size(); i++) {
            final int index = i;
            final String payload = String.join("\n", activeSplits.get(index));
            mapFutures.add(taskPool.submit(
                    () -> runTaskWithRetry(TaskType.MAP, job.name(), payload, index, report)));
        }

        List<String> mapOutputs = new ArrayList<>();
        for (Future<String> future : mapFutures) {
            try {
                mapOutputs.add(future.get());
            } catch (ExecutionException ee) {
                if (ee.getCause() instanceof IOException ioe) {
                    throw ioe;
                }
                throw new IOException("Map phase failed: " + ee.getCause().getMessage(), ee.getCause());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Map phase interrupted", ie);
            }
        }
        report.setMapMillis((System.nanoTime() - mapStart) / 1_000_000.0);

        // ---------------------------------------------------------------- 3. SHUFFLE
        long shuffleStart = System.nanoTime();
        Map<String, List<String>> grouped = new TreeMap<>();
        long rawPairs = 0;
        long shippedPairs = 0;

        for (String output : mapOutputs) {
            MapTask.Result decoded = MapTask.decode(output);
            rawPairs += decoded.rawPairsCount();
            shippedPairs += decoded.pairs().size();
            for (KeyValuePair kv : decoded.pairs()) {
                grouped.computeIfAbsent(kv.key(), k -> new ArrayList<>()).add(kv.value());
            }
        }
        report.setPairsEmitted(rawPairs);
        report.setPairsAfterCombine(shippedPairs);
        report.setShuffleKeys(grouped.size());

        List<Map<String, List<String>>> partitions = HashPartitioner.partition(grouped, reducers);
        report.setShuffleMillis((System.nanoTime() - shuffleStart) / 1_000_000.0);

        // ---------------------------------------------------------------- 4. REDUCE
        long reduceStart = System.nanoTime();
        List<Integer> activePartitionIndices = new ArrayList<>();
        for (int i = 0; i < partitions.size(); i++) {
            if (!partitions.get(i).isEmpty()) {
                activePartitionIndices.add(i);
            }
        }
        report.setReduceTasks(activePartitionIndices.size());

        List<Future<String>> reduceFutures = new ArrayList<>();
        for (int i = 0; i < activePartitionIndices.size(); i++) {
            int partitionIndex = activePartitionIndices.get(i);
            Map<String, List<String>> partition = partitions.get(partitionIndex);
            String payload = ReduceTask.encodePartition(partition);
            final int index = i;
            reduceFutures.add(taskPool.submit(
                    () -> runTaskWithRetry(TaskType.REDUCE, job.name(), payload, index, report)));
        }

        List<String> reduceOutputs = new ArrayList<>();
        for (Future<String> future : reduceFutures) {
            try {
                reduceOutputs.add(future.get());
            } catch (ExecutionException ee) {
                if (ee.getCause() instanceof IOException ioe) {
                    throw ioe;
                }
                throw new IOException("Reduce phase failed: " + ee.getCause().getMessage(), ee.getCause());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Reduce phase interrupted", ie);
            }
        }
        report.setReduceMillis((System.nanoTime() - reduceStart) / 1_000_000.0);

        // ---------------------------------------------------------------- 5. COLLECT
        Map<String, String> results = new TreeMap<>();
        for (String output : reduceOutputs) {
            Map<String, String> decoded = ReduceTask.decodeResult(output);
            results.putAll(decoded);
        }
        report.setTotalMillis((System.nanoTime() - jobStart) / 1_000_000.0);
        return results;
    }

    private String runTaskWithRetry(TaskType type, String jobName, String payload,
                                    int preferredIndex, JobReport report) throws IOException {
        IOException lastException = null;
        for (int attempt = 0; attempt < workerIds.size(); attempt++) {
            int nodeId = workerIds.get(Math.floorMod(preferredIndex + attempt, workerIds.size()));
            Future<String> attemptFuture = taskPool.submit(
                    () -> transport.executeTask(nodeId, type, jobName, payload));
            try {
                String result = attemptFuture.get(taskTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (result == null) {
                    throw new IOException("Worker node " + nodeId + " returned null output");
                }
                if (type == TaskType.MAP) {
                    report.countMapTask(nodeId);
                } else {
                    report.countReduceTask(nodeId);
                }
                if (attempt > 0) {
                    report.addRetry();
                }
                return result;
            } catch (TimeoutException te) {
                attemptFuture.cancel(true);
                lastException = new IOException("Task timed out after " + taskTimeout.toMillis()
                        + " ms on worker " + nodeId, te);
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof IOException ioe) {
                    lastException = ioe;
                } else {
                    lastException = new IOException("Worker node " + nodeId + " failed: "
                            + (cause != null ? cause.getMessage() : "unknown error"), cause);
                }
            } catch (InterruptedException ie) {
                attemptFuture.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("Task execution interrupted on worker " + nodeId, ie);
            }
        }
        throw new IOException("All workers exhausted for " + type + " task (job=" + jobName
                + ", preferredIndex=" + preferredIndex + ")", lastException);
    }

    /**
     * Runs a job locally in-memory using the standard registry and cleans up resources.
     */
    public static Map<String, String> runLocal(MapReduceJob job, List<String> input,
                                               int numWorkers, JobReport report) throws IOException {
        List<Integer> ids = new ArrayList<>();
        for (int i = 1; i <= Math.max(1, numWorkers); i++) {
            ids.add(i);
        }
        JobRegistry registry = new JobRegistry();
        registry.register(job);
        TaskTransport transport = TaskTransport.inMemory(registry);
        MapReducePipeline pipeline = new MapReducePipeline(ids, transport);
        try {
            return pipeline.run(job, input, report);
        } finally {
            pipeline.shutdown();
        }
    }

    /**
     * Bounded shutdown awaiting thread termination within 5 seconds.
     */
    public void shutdown() {
        if (ownsExecutor) {
            taskPool.shutdownNow();
            try {
                if (!taskPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "MapReducePipeline thread pool failed to terminate within 5 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for thread pool termination", e);
            }
        }
    }

    public List<Integer> workerIds() {
        return workerIds;
    }

    public int reducers() {
        return reducers;
    }

    public Duration taskTimeout() {
        return taskTimeout;
    }
}
