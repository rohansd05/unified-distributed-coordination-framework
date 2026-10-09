package com.udcf.modules.mapreduce;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Execution measurements and statistics for one completed MapReduce job.
 *
 * <p>All accessors are thread-safe. In accordance with Rule R7 (honesty), timing
 * fields remain {@code null} until genuinely measured rather than defaulting to zero.</p>
 */
public class JobReport {

    private final String jobName;
    private int inputLines;
    private int splits;
    private int mapTasks;
    private int reduceTasks;
    private long pairsEmitted;
    private long pairsAfterCombine;
    private int shuffleKeys;
    private int failedTasksRetried;

    private Double mapMillis;
    private Double shuffleMillis;
    private Double reduceMillis;
    private Double totalMillis;

    private final Map<Integer, Integer> mapTasksPerNode = new LinkedHashMap<>();
    private final Map<Integer, Integer> reduceTasksPerNode = new LinkedHashMap<>();

    public JobReport(String jobName) {
        this.jobName = Objects.requireNonNull(jobName, "jobName must not be null");
    }

    public synchronized void countMapTask(int nodeId) {
        mapTasksPerNode.merge(nodeId, 1, Integer::sum);
    }

    public synchronized void countReduceTask(int nodeId) {
        reduceTasksPerNode.merge(nodeId, 1, Integer::sum);
    }

    public synchronized void addRetry() {
        failedTasksRetried++;
    }

    /**
     * Percentage of mapper output pairs eliminated by the local combiner before shuffle,
     * or {@code null} if no pairs were emitted (undefined saving per Rule R7).
     */
    public synchronized Double combinerSavingPercent() {
        if (pairsEmitted == 0) {
            return null;
        }
        return round(100.0 * (pairsEmitted - pairsAfterCombine) / pairsEmitted);
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    public synchronized String jobName() {
        return jobName;
    }

    public synchronized int inputLines() {
        return inputLines;
    }

    public synchronized void setInputLines(int inputLines) {
        this.inputLines = inputLines;
    }

    public synchronized int splits() {
        return splits;
    }

    public synchronized void setSplits(int splits) {
        this.splits = splits;
    }

    public synchronized int mapTasks() {
        return mapTasks;
    }

    public synchronized void setMapTasks(int mapTasks) {
        this.mapTasks = mapTasks;
    }

    public synchronized int reduceTasks() {
        return reduceTasks;
    }

    public synchronized void setReduceTasks(int reduceTasks) {
        this.reduceTasks = reduceTasks;
    }

    public synchronized long pairsEmitted() {
        return pairsEmitted;
    }

    public synchronized void setPairsEmitted(long pairsEmitted) {
        this.pairsEmitted = pairsEmitted;
    }

    public synchronized long pairsAfterCombine() {
        return pairsAfterCombine;
    }

    public synchronized void setPairsAfterCombine(long pairsAfterCombine) {
        this.pairsAfterCombine = pairsAfterCombine;
    }

    public synchronized int shuffleKeys() {
        return shuffleKeys;
    }

    public synchronized void setShuffleKeys(int shuffleKeys) {
        this.shuffleKeys = shuffleKeys;
    }

    public synchronized int failedTasksRetried() {
        return failedTasksRetried;
    }

    public synchronized void setFailedTasksRetried(int failedTasksRetried) {
        this.failedTasksRetried = failedTasksRetried;
    }

    public synchronized Double mapMillis() {
        return mapMillis == null ? null : round(mapMillis);
    }

    public synchronized void setMapMillis(Double mapMillis) {
        this.mapMillis = mapMillis;
    }

    public synchronized Double shuffleMillis() {
        return shuffleMillis == null ? null : round(shuffleMillis);
    }

    public synchronized void setShuffleMillis(Double shuffleMillis) {
        this.shuffleMillis = shuffleMillis;
    }

    public synchronized Double reduceMillis() {
        return reduceMillis == null ? null : round(reduceMillis);
    }

    public synchronized void setReduceMillis(Double reduceMillis) {
        this.reduceMillis = reduceMillis;
    }

    public synchronized Double totalMillis() {
        return totalMillis == null ? null : round(totalMillis);
    }

    public synchronized void setTotalMillis(Double totalMillis) {
        this.totalMillis = totalMillis;
    }

    public synchronized Map<Integer, Integer> mapTasksPerNode() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(mapTasksPerNode));
    }

    public synchronized Map<Integer, Integer> reduceTasksPerNode() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(reduceTasksPerNode));
    }
}
