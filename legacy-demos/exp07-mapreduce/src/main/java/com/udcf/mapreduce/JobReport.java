package com.udcf.mapreduce;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Measurements for one completed job. Every figure is counted during the real run.
 */
public class JobReport {

    private final String jobName;
    private int inputLines;
    private int splits;
    private int mapTasks;
    private int reduceTasks;
    private long pairsEmitted;        // before the combiner
    private long pairsAfterCombine;   // what actually crossed the network
    private int shuffleKeys;
    private int failedTasksRetried;
    private double mapMillis;
    private double shuffleMillis;
    private double reduceMillis;
    private double totalMillis;
    private final Map<Integer, Integer> mapTasksPerNode = new LinkedHashMap<>();
    private final Map<Integer, Integer> reduceTasksPerNode = new LinkedHashMap<>();

    public JobReport(String jobName) {
        this.jobName = jobName;
    }

    // Called from several task threads at once, so these three are synchronized.
    // Everything else on this class is written once, after all tasks have joined.
    public synchronized void countMapTask(int nodeId) {
        mapTasksPerNode.merge(nodeId, 1, Integer::sum);
    }

    public synchronized void countReduceTask(int nodeId) {
        reduceTasksPerNode.merge(nodeId, 1, Integer::sum);
    }

    /** Proportion of mapper output the combiner removed before the shuffle. */
    public double combinerSavingPercent() {
        if (pairsEmitted == 0) { return 0; }
        return round(100.0 * (pairsEmitted - pairsAfterCombine) / pairsEmitted);
    }

    private static double round(double v) { return Math.round(v * 10d) / 10d; }

    public String jobName()                       { return jobName; }
    public int inputLines()                       { return inputLines; }
    public void setInputLines(int v)              { inputLines = v; }
    public int splits()                           { return splits; }
    public void setSplits(int v)                  { splits = v; }
    public int mapTasks()                         { return mapTasks; }
    public void setMapTasks(int v)                { mapTasks = v; }
    public int reduceTasks()                      { return reduceTasks; }
    public void setReduceTasks(int v)             { reduceTasks = v; }
    public long pairsEmitted()                    { return pairsEmitted; }
    public void setPairsEmitted(long v)           { pairsEmitted = v; }
    public long pairsAfterCombine()               { return pairsAfterCombine; }
    public void setPairsAfterCombine(long v)      { pairsAfterCombine = v; }
    public int shuffleKeys()                      { return shuffleKeys; }
    public void setShuffleKeys(int v)             { shuffleKeys = v; }
    public int failedTasksRetried()               { return failedTasksRetried; }
    public synchronized void addRetry()           { failedTasksRetried++; }
    public double mapMillis()                     { return round(mapMillis); }
    public void setMapMillis(double v)            { mapMillis = v; }
    public double shuffleMillis()                 { return round(shuffleMillis); }
    public void setShuffleMillis(double v)        { shuffleMillis = v; }
    public double reduceMillis()                  { return round(reduceMillis); }
    public void setReduceMillis(double v)         { reduceMillis = v; }
    public double totalMillis()                   { return round(totalMillis); }
    public void setTotalMillis(double v)          { totalMillis = v; }
    public Map<Integer, Integer> mapTasksPerNode()    { return mapTasksPerNode; }
    public Map<Integer, Integer> reduceTasksPerNode() { return reduceTasksPerNode; }
}
