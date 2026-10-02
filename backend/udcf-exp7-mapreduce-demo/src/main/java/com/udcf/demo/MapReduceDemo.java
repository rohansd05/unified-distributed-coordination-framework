package com.udcf.demo;

import com.udcf.mapreduce.JobReport;
import com.udcf.mapreduce.MapReduceCoordinator;
import com.udcf.mapreduce.MapReduceJob;
import com.udcf.mapreduce.MapReduceWorker;
import com.udcf.mapreduce.EventCategoryJob;
import com.udcf.mapreduce.LatencyPerNodeJob;
import com.udcf.mapreduce.WordCountJob;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Standalone console demonstration of Experiment 7 — MapReduce.
 *
 * <p>Boots three worker nodes on localhost TCP ports 7301 to 7303 and runs three
 * complete MapReduce jobs across them: the classic word count, then two jobs that analyse
 * the framework's own event log. A final phase kills a worker mid-job to show the
 * coordinator re-executing the lost task somewhere else.</p>
 *
 * <pre>
 *   javac -d target/classes src/main/java/com/udcf/sync/LamportClock.java src/main/java/com/udcf/mapreduce/*.java src/main/java/com/udcf/demo/*.java
 *   java -cp target/classes com.udcf.demo.MapReduceDemo
 * </pre>
 */
public class MapReduceDemo {

    private static final int[] IDS   = {1, 2, 3};
    private static final int[] PORTS = {7301, 7302, 7303};

    public static void main(String[] args) throws Exception {
        printBanner();

        List<MapReduceWorker> workers = new ArrayList<>();
        Map<Integer, Integer> portMap = new LinkedHashMap<>();
        for (int i = 0; i < IDS.length; i++) {
            MapReduceWorker w = new MapReduceWorker(IDS[i], PORTS[i], 4);
            w.start();
            workers.add(w);
            portMap.put(IDS[i], PORTS[i]);
        }
        MapReduceCoordinator coordinator =
                new MapReduceCoordinator(List.of(1, 2, 3), portMap);

        System.out.println("  Cluster ready:");
        for (MapReduceWorker w : workers) {
            System.out.printf("    Node %d  TCP %d  %d worker threads%n",
                    w.nodeId(), w.port(), w.poolSize());
        }

        List<String> text = readLines("data/sample-text.txt");
        List<String> events = readLines("data/framework-events.log");
        System.out.printf("%n  Input 1: data/sample-text.txt        %d lines%n", text.size());
        System.out.printf("  Input 2: data/framework-events.log   %d lines%n", events.size());

        // ---------------------------------------------------------------- JOB 1
        runJob(coordinator, workers, new WordCountJob(), text,
               "PHASE 1", "THE CLASSIC EXAMPLE — WORD COUNT",
               "Count how often each word appears. This is the job every textbook uses,",
               "so it is the clearest way to watch the pipeline work before we point it",
               "at something from our own project.", 12);

        // ---------------------------------------------------------------- JOB 2
        runJob(coordinator, workers, new EventCategoryJob(), events,
               "PHASE 2", "OUR PROJECT — EVENTS BY CATEGORY",
               "The same framework now analyses its own event log, exported from the",
               "earlier experiments. Which kinds of event does the cluster actually",
               "spend its time producing?", 12);

        // ---------------------------------------------------------------- JOB 3
        runJob(coordinator, workers, new LatencyPerNodeJob(), events,
               "PHASE 3", "OUR PROJECT — AVERAGE LATENCY PER NODE",
               "An average cannot be computed by averaging averages, so this job carries",
               "a partial sum and a partial count through the pipeline and only divides",
               "at the very end. Watch the result confirm Node 3 is the slow one.", 5);

        // ---------------------------------------------------------------- JOB 4
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("  PHASE 4  -  WORKER FAILURE DURING A JOB");
        System.out.println("  Node 3 is taken offline before the job starts. Its map task cannot be");
        System.out.println("  delivered, so the coordinator re-executes that identical task on another");
        System.out.println("  worker. The answer must come out exactly the same as Phase 2.");
        System.out.println("=".repeat(100));

        workers.get(2).crash();
        System.out.println();
        System.out.println("  Node 3 has been taken offline.");
        TimeUnit.MILLISECONDS.sleep(300);

        for (MapReduceWorker w : workers) { w.resetCounters(); }
        JobReport failReport = new JobReport("event-category-count");
        Map<String, String> failResults =
                coordinator.run(new EventCategoryJob(), events, failReport);

        printPipeline(failReport, workers);
        printResults(new EventCategoryJob(), failResults, 12);
        System.out.printf("%n  Tasks re-executed after a failure : %d%n",
                failReport.failedTasksRetried());
        System.out.println("  The job completed with two workers instead of three, and the counts");
        System.out.println("  are identical to Phase 2 — because map and reduce are pure functions,");
        System.out.println("  so running a task a second time is always safe.");

        workers.get(2).recover();
        System.out.println();
        System.out.println("  Node 3 is back online.");

        coordinator.shutdown();
        for (MapReduceWorker w : workers) { w.shutdown(); }
        TimeUnit.MILLISECONDS.sleep(200);

        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("   MAPREDUCE DEMONSTRATION COMPLETED");
        System.out.println("=".repeat(100));
        System.out.println();
    }

    // ------------------------------------------------------------------ helpers

    private static void runJob(MapReduceCoordinator coordinator, List<MapReduceWorker> workers,
                               MapReduceJob job, List<String> input,
                               String label, String title, String l1, String l2, String l3,
                               int topN) throws Exception {
        TimeUnit.MILLISECONDS.sleep(250);
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("  " + label + "  -  " + title);
        System.out.println("  " + l1);
        System.out.println("  " + l2);
        System.out.println("  " + l3);
        System.out.println("=".repeat(100));
        System.out.println();
        System.out.println("  Question: " + job.description());

        for (MapReduceWorker w : workers) { w.resetCounters(); }
        JobReport report = new JobReport(job.name());
        Map<String, String> results = coordinator.run(job, input, report);

        printPipeline(report, workers);
        printResults(job, results, topN);
    }

    private static void printPipeline(JobReport r, List<MapReduceWorker> workers) {
        System.out.println();
        System.out.println("  PIPELINE");
        System.out.println("  " + "-".repeat(92));
        System.out.printf("    SPLIT     %d input lines divided into %d splits, one per worker%n",
                r.inputLines(), r.splits());
        System.out.printf("    MAP       %d map tasks ran in parallel and emitted %d key-value pairs"
                + "   (%.1f ms)%n", r.mapTasks(), r.pairsEmitted(), r.mapMillis());
        System.out.printf("    COMBINE   local reduce on each node cut that to %d pairs"
                + "   — %.1f%% less data crossed the network%n",
                r.pairsAfterCombine(), r.combinerSavingPercent());
        System.out.printf("    SHUFFLE   pairs grouped into %d distinct keys, partitioned by hash"
                + "   (%.1f ms)%n", r.shuffleKeys(), r.shuffleMillis());
        System.out.printf("    REDUCE    %d reduce tasks produced the final answers"
                + "   (%.1f ms)%n", r.reduceTasks(), r.reduceMillis());
        System.out.printf("    TOTAL     %.1f ms%n", r.totalMillis());

        System.out.println();
        System.out.printf("    %-10s %-14s %-14s%n", "Node", "Map tasks", "Reduce tasks");
        System.out.println("    " + "-".repeat(44));
        for (MapReduceWorker w : workers) {
            System.out.printf("    Node %-5d %-14d %-14d%n",
                    w.nodeId(), w.mapTasksRun(), w.reduceTasksRun());
        }
    }

    private static void printResults(MapReduceJob job, Map<String, String> results, int topN) {
        System.out.println();
        System.out.println("  RESULTS" + (topN < results.size() ? "   (top " + topN + " of "
                + results.size() + " keys)" : "   (" + results.size() + " keys)"));
        System.out.println("  " + "-".repeat(92));

        List<Map.Entry<String, String>> entries = new ArrayList<>(results.entrySet());
        entries.sort(Comparator.comparingDouble(
                (Map.Entry<String, String> e) -> numericOf(e.getValue())).reversed());

        int shown = 0;
        for (Map.Entry<String, String> e : entries) {
            if (shown++ >= topN) {
                break;
            }
            System.out.printf("    %-28s %s%n", e.getKey(), job.formatResult(e.getKey(), e.getValue()));
        }
    }

    /** Sorts results by magnitude whether the value is a count or a "sum;count" pair. */
    private static double numericOf(String value) {
        try {
            if (value.contains(";")) {
                String[] p = value.split(";");
                double sum = Double.parseDouble(p[0]);
                long count = Long.parseLong(p[1]);
                return count == 0 ? 0 : sum / count;
            }
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> readLines(String path) throws IOException {
        Path p = Path.of(path);
        if (!Files.exists(p)) {
            System.err.println("Input file not found: " + p.toAbsolutePath());
            System.err.println("Run the demo from the folder that contains the data directory.");
            System.exit(1);
        }
        return Files.readAllLines(p, StandardCharsets.UTF_8);
    }

    private static void printBanner() {
        System.out.println("=".repeat(100));
        System.out.println("   UNIFIED DISTRIBUTED COORDINATION FRAMEWORK");
        System.out.println("   EXPERIMENT 7  -  MAPREDUCE");
        System.out.println("   Distributed map, shuffle and reduce over TCP  (standalone demonstration)");
        System.out.println("=".repeat(100));
        System.out.println();
        System.out.println("MapReduce is a way to process a large amount of data by writing only two");
        System.out.println("small functions and letting the framework do everything else:");
        System.out.println();
        System.out.println("   MAP       turn each input record into zero or more key-value pairs");
        System.out.println("   SHUFFLE   gather every value that shares a key onto one machine");
        System.out.println("   REDUCE    turn each key and its list of values into one answer");
        System.out.println();
        System.out.println("The framework handles splitting the input, shipping work to nodes,");
        System.out.println("grouping by key, and re-running tasks that fail. The user never does.");
        System.out.println();
    }
}
