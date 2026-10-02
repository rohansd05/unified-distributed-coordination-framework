package com.udcf.mapreduce;

import com.udcf.sync.LamportClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A worker node that executes map and reduce tasks sent to it by the coordinator.
 *
 * <p>The worker holds no job logic of its own. It receives a job <i>name</i> and a block
 * of data, looks the job up in the {@link JobRegistry}, and runs the supplied functions
 * on its Experiment 2 thread pool. That is why the same three nodes can execute any job
 * without being redeployed.</p>
 *
 * <p>Task payloads are Base64 encoded before being put on the wire. The protocol is
 * line-based and the data contains newlines and pipe characters, so encoding removes any
 * possibility of a record being split in the wrong place.</p>
 */
public class MapReduceWorker {

    private final int nodeId;
    private final int port;
    private final ThreadPoolExecutor pool;
    private final LamportClock clock = new LamportClock();
    private final AtomicInteger mapTasksRun = new AtomicInteger();
    private final AtomicInteger reduceTasksRun = new AtomicInteger();

    private ServerSocket serverSocket;
    private volatile boolean running = true;
    private volatile boolean alive = true;

    public MapReduceWorker(int nodeId, int port, int threads) {
        this.nodeId = nodeId;
        this.port = port;
        this.pool = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                r -> {
                    Thread t = new Thread(r, "mr-node" + nodeId);
                    t.setDaemon(true);
                    return t;
                });
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
        Thread accept = new Thread(this::acceptLoop, "mr-node" + nodeId + "-accept");
        accept.setDaemon(true);
        accept.start();
    }

    /** Simulates a node crash so the coordinator must re-run its task elsewhere. */
    public void crash() {
        alive = false;
        close();
    }

    public void recover() throws IOException {
        alive = true;
        start();
    }

    public void shutdown() {
        running = false;
        close();
        pool.shutdownNow();
    }

    private void close() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // Already closed; nothing to do.
        }
    }

    private void acceptLoop() {
        while (running && alive) {
            try {
                Socket client = serverSocket.accept();
                final Socket accepted = client;
                pool.execute(() -> serve(accepted));
            } catch (IOException e) {
                return;   // closed by crash() or shutdown()
            }
        }
    }

    private void serve(Socket client) {
        try (Socket c = client;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(c.getOutputStream(), true)) {

            String header = in.readLine();
            if (header == null) {
                return;
            }
            String[] parts = header.split("\\|", 5);
            String type = parts[0];
            String jobName = parts[1];
            clock.update(Long.parseLong(parts[2]));
            String payload = parts[4];

            String body = new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
            MapReduceJob job = JobRegistry.get(jobName);

            String result = type.equals("MAP")
                    ? runMap(job, body)
                    : runReduce(job, body);

            out.println("OK|" + nodeId + "|" + clock.tick() + "|"
                    + Base64.getEncoder().encodeToString(result.getBytes(StandardCharsets.UTF_8)));

        } catch (IOException e) {
            if (running) {
                System.err.println("Worker " + nodeId + " error: " + e.getMessage());
            }
        }
    }

    /**
     * Runs the map function over every line of the split, then applies the combiner
     * locally so that only the reduced output has to travel back over the network.
     */
    private String runMap(MapReduceJob job, String split) {
        mapTasksRun.incrementAndGet();
        List<String[]> emitted = new ArrayList<>();

        for (String line : split.split("\n")) {
            if (!line.isBlank()) {
                job.map(line, (k, v) -> emitted.add(new String[]{k, v}));
            }
        }
        long rawCount = emitted.size();

        if (!job.usesCombiner()) {
            return encodePairs(emitted, rawCount);
        }

        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String[] kv : emitted) {
            grouped.computeIfAbsent(kv[0], k -> new ArrayList<>()).add(kv[1]);
        }
        List<String[]> combined = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : grouped.entrySet()) {
            combined.add(new String[]{e.getKey(), job.combine(e.getKey(), e.getValue())});
        }
        return encodePairs(combined, rawCount);
    }

    /** First line reports how many pairs the mapper emitted before combining. */
    private String encodePairs(List<String[]> pairs, long rawCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("#raw=").append(rawCount).append('\n');
        for (String[] kv : pairs) {
            sb.append(kv[0]).append('\t').append(kv[1]).append('\n');
        }
        return sb.toString();
    }

    /** Input is one key per line with its values separated by \u0001. */
    private String runReduce(MapReduceJob job, String partition) {
        reduceTasksRun.incrementAndGet();
        StringBuilder sb = new StringBuilder();

        for (String line : partition.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int tab = line.indexOf('\t');
            String key = line.substring(0, tab);
            List<String> values = new ArrayList<>();
            for (String v : line.substring(tab + 1).split("\u0001")) {
                if (!v.isEmpty()) {
                    values.add(v);
                }
            }
            sb.append(key).append('\t').append(job.reduce(key, values)).append('\n');
        }
        return sb.toString();
    }

    public int nodeId()        { return nodeId; }
    public int port()          { return port; }
    public int poolSize()      { return pool.getCorePoolSize(); }
    public int mapTasksRun()   { return mapTasksRun.get(); }
    public int reduceTasksRun(){ return reduceTasksRun.get(); }
    public boolean isAlive()   { return alive; }
    public void resetCounters(){ mapTasksRun.set(0); reduceTasksRun.set(0); }
}
