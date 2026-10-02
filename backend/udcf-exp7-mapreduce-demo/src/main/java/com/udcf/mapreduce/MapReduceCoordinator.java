package com.udcf.mapreduce;

import com.udcf.sync.LamportClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The coordinator: it runs the whole MapReduce pipeline and owns every decision the
 * user's two functions do not make.
 *
 * <p>Its responsibilities are exactly the ones Hadoop's JobTracker has:</p>
 * <ol>
 *   <li><b>Split</b> the input into blocks, one per worker.</li>
 *   <li><b>Map</b> — ship each split to a worker and collect the pairs it emits.</li>
 *   <li><b>Shuffle</b> — group every pair by key, then partition the keys across reducers
 *       using a hash, so all values for one key always land on the same reducer.</li>
 *   <li><b>Reduce</b> — ship each partition out and collect the final answers.</li>
 *   <li><b>Retry</b> — if a worker dies mid-task, re-run that task somewhere else.</li>
 * </ol>
 *
 * <p>The user wrote only map and reduce. Everything above is why MapReduce is called a
 * framework rather than an algorithm.</p>
 */
public class MapReduceCoordinator {

    private static final int TASK_TIMEOUT_MS = 15000;

    private final List<Integer> workerIds;
    private final Map<Integer, Integer> workerPorts;
    private final LamportClock clock = new LamportClock();
    private final ExecutorService taskPool;

    public MapReduceCoordinator(List<Integer> workerIds, Map<Integer, Integer> workerPorts) {
        this.workerIds = workerIds;
        this.workerPorts = workerPorts;
        this.taskPool = Executors.newFixedThreadPool(Math.max(2, workerIds.size()), r -> {
            Thread t = new Thread(r, "mr-coordinator");
            t.setDaemon(true);
            return t;
        });
    }

    /** Runs a complete job and returns both the results and the measurements. */
    public Map<String, String> run(MapReduceJob job, List<String> input, JobReport report) {
        long jobStart = System.nanoTime();
        report.setInputLines(input.size());

        // ---------------------------------------------------------------- 1. SPLIT
        List<List<String>> splits = split(input, workerIds.size());
        report.setSplits(splits.size());
        report.setMapTasks(splits.size());

        // ---------------------------------------------------------------- 2. MAP
        long mapStart = System.nanoTime();
        List<Callable<String>> mapTasks = new ArrayList<>();
        for (int i = 0; i < splits.size(); i++) {
            final int index = i;
            final String payload = String.join("\n", splits.get(index));
            mapTasks.add(() -> runTaskWithRetry("MAP", job.name(), payload, index, report, true));
        }
        List<String> mapOutputs = invokeAll(mapTasks);
        report.setMapMillis((System.nanoTime() - mapStart) / 1_000_000d);

        // ---------------------------------------------------------------- 3. SHUFFLE
        long shuffleStart = System.nanoTime();
        Map<String, List<String>> grouped = new TreeMap<>();
        long rawPairs = 0;
        long shippedPairs = 0;

        for (String output : mapOutputs) {
            if (output == null) {
                continue;
            }
            for (String line : output.split("\n")) {
                if (line.startsWith("#raw=")) {
                    rawPairs += Long.parseLong(line.substring(5).trim());
                    continue;
                }
                if (line.isBlank()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                grouped.computeIfAbsent(line.substring(0, tab), k -> new ArrayList<>())
                       .add(line.substring(tab + 1));
                shippedPairs++;
            }
        }
        report.setPairsEmitted(rawPairs);
        report.setPairsAfterCombine(shippedPairs);
        report.setShuffleKeys(grouped.size());

        // Partition keys across reducers. Hashing guarantees that every value for a
        // given key reaches the same reducer, which is what makes reduce correct.
        int reducers = workerIds.size();
        List<Map<String, List<String>>> partitions = new ArrayList<>();
        for (int i = 0; i < reducers; i++) {
            partitions.add(new LinkedHashMap<>());
        }
        for (Map.Entry<String, List<String>> e : grouped.entrySet()) {
            int p = Math.floorMod(e.getKey().hashCode(), reducers);
            partitions.get(p).put(e.getKey(), e.getValue());
        }
        report.setShuffleMillis((System.nanoTime() - shuffleStart) / 1_000_000d);

        // ---------------------------------------------------------------- 4. REDUCE
        long reduceStart = System.nanoTime();
        List<Callable<String>> reduceTasks = new ArrayList<>();
        int reduceCount = 0;
        for (int i = 0; i < partitions.size(); i++) {
            Map<String, List<String>> partition = partitions.get(i);
            if (partition.isEmpty()) {
                continue;
            }
            reduceCount++;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, List<String>> e : partition.entrySet()) {
                sb.append(e.getKey()).append('\t')
                  .append(String.join("\u0001", e.getValue())).append('\n');
            }
            final int index = i;
            final String payload = sb.toString();
            reduceTasks.add(() -> runTaskWithRetry("REDUCE", job.name(), payload, index, report, false));
        }
        report.setReduceTasks(reduceCount);
        List<String> reduceOutputs = invokeAll(reduceTasks);
        report.setReduceMillis((System.nanoTime() - reduceStart) / 1_000_000d);

        // ---------------------------------------------------------------- 5. COLLECT
        Map<String, String> results = new TreeMap<>();
        for (String output : reduceOutputs) {
            if (output == null) {
                continue;
            }
            for (String line : output.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                results.put(line.substring(0, tab), line.substring(tab + 1));
            }
        }
        report.setTotalMillis((System.nanoTime() - jobStart) / 1_000_000d);
        return results;
    }

    /** Contiguous blocks, one per worker, as evenly sized as the input allows. */
    private List<List<String>> split(List<String> input, int parts) {
        List<List<String>> splits = new ArrayList<>();
        int size = (int) Math.ceil(input.size() / (double) parts);
        for (int i = 0; i < input.size(); i += size) {
            splits.add(new ArrayList<>(input.subList(i, Math.min(input.size(), i + size))));
        }
        while (splits.size() < parts) {
            splits.add(new ArrayList<>());
        }
        return splits;
    }

    /**
     * Sends a task to its preferred worker and, if that worker is unreachable, re-runs
     * the identical task on another one.
     *
     * <p>This is safe only because map and reduce are pure functions of their input:
     * running a task twice produces the same answer. Task re-execution is how real
     * MapReduce tolerates machine failure, and it is the reason the programming model
     * forbids side effects inside map and reduce.</p>
     */
    private String runTaskWithRetry(String type, String jobName, String payload,
                                    int preferredIndex, JobReport report, boolean isMap) {
        for (int attempt = 0; attempt < workerIds.size(); attempt++) {
            int nodeId = workerIds.get(Math.floorMod(preferredIndex + attempt, workerIds.size()));
            try {
                String result = send(nodeId, type, jobName, payload);
                if (isMap) {
                    report.countMapTask(nodeId);
                } else {
                    report.countReduceTask(nodeId);
                }
                if (attempt > 0) {
                    report.addRetry();
                    System.out.printf("      ! %s task %d failed on its first worker — "
                            + "re-executed on Node %d%n", type, preferredIndex, nodeId);
                }
                return result;
            } catch (IOException e) {
                // Worker unreachable; fall through and try the next one.
            }
        }
        System.out.printf("      ! %s task %d could not be run on any worker%n", type, preferredIndex);
        return null;
    }

    private String send(int nodeId, String type, String jobName, String payload) throws IOException {
        long stamped = clock.tick();
        String encoded = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", workerPorts.get(nodeId)), 2000);
            socket.setSoTimeout(TASK_TIMEOUT_MS);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            out.println(type + "|" + jobName + "|" + stamped + "|0|" + encoded);
            String response = in.readLine();
            if (response == null) {
                throw new IOException("no reply from node " + nodeId);
            }
            String[] parts = response.split("\\|", 4);
            clock.update(Long.parseLong(parts[2]));
            return new String(Base64.getDecoder().decode(parts[3]), StandardCharsets.UTF_8);
        }
    }

    private List<String> invokeAll(List<Callable<String>> tasks) {
        List<String> outputs = new ArrayList<>();
        try {
            for (Future<String> f : taskPool.invokeAll(tasks)) {
                outputs.add(f.get());
            }
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
        return outputs;
    }

    public void shutdown() { taskPool.shutdownNow(); }
    public long clockValue() { return clock.current(); }
}
