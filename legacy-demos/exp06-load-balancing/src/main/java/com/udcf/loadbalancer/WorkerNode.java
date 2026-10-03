package com.udcf.loadbalancer;

import com.udcf.sync.LamportClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One worker node behind the load balancer.
 *
 * <p>Each worker listens on its own TCP port and processes requests on the bounded
 * thread pool built in Experiment 2. The work is real SHA-256 hashing, not a sleep, so
 * a worker with fewer threads or a larger cost factor genuinely takes longer and
 * genuinely builds a queue.</p>
 *
 * <p>The three workers are deliberately unequal. On identical workers every balancing
 * algorithm produces the same distribution and the comparison demonstrates nothing; the
 * whole point of Experiment 6 is visible only when capacity differs.</p>
 */
public class WorkerNode {

    private final int nodeId;
    private final int port;
    private final String label;

    /** Multiplies the hashing work, so a higher factor means a slower worker. */
    private final int costFactor;

    private final ThreadPoolExecutor pool;
    private final LamportClock clock = new LamportClock();
    private final AtomicInteger served = new AtomicInteger();
    private final AtomicInteger peakQueue = new AtomicInteger();

    private ServerSocket serverSocket;
    private volatile boolean running = true;
    private volatile boolean alive = true;

    public WorkerNode(int nodeId, int port, String label, int threads, int costFactor) {
        this.nodeId = nodeId;
        this.port = port;
        this.label = label;
        this.costFactor = costFactor;
        this.pool = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                r -> {
                    Thread t = new Thread(r, "node" + nodeId + "-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
        Thread accept = new Thread(this::acceptLoop, "node" + nodeId + "-accept");
        accept.setDaemon(true);
        accept.start();
    }

    /** Simulates a crash: the port closes, so the balancer's connection is refused. */
    public void crash() {
        alive = false;
        closeSocket();
    }

    public void recover() throws IOException {
        alive = true;
        start();
    }

    public void shutdown() {
        running = false;
        closeSocket();
        pool.shutdownNow();
    }

    private void closeSocket() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // Closing an already-closed socket is not worth reporting.
        }
    }

    private void acceptLoop() {
        while (running && alive) {
            Socket client = null;
            try {
                client = serverSocket.accept();
                final Socket accepted = client;
                // Each connection is handed to the pool. The accept thread stays free,
                // so a backlog shows up as queue depth rather than refused connections.
                pool.execute(() -> serve(accepted));
            } catch (IOException e) {
                return;   // socket closed by crash() or shutdown(); expected
            } catch (RejectedExecutionException e) {
                // Queue full: refuse this one request rather than dropping the worker.
                closeQuietly(client);
            }
        }
    }

    private void closeQuietly(Socket s) {
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { /* nothing to do */ }
        }
    }

    private void serve(Socket client) {
        int queued = pool.getQueue().size();
        peakQueue.accumulateAndGet(queued, Math::max);

        try (Socket c = client;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(c.getOutputStream(), true)) {

            String line = in.readLine();
            if (line == null) {
                return;
            }
            String[] parts = line.split("\\|", 4);
            long senderClock = Long.parseLong(parts[2]);
            clock.update(senderClock);                    // Lamport Rule 3

            if (parts[0].equals("HEALTH")) {
                out.println("OK|" + nodeId + "|" + clock.tick() + "|"
                        + pool.getActiveCount() + ";" + pool.getQueue().size());
                return;
            }

            int units = Integer.parseInt(parts[3]);
            long start = System.nanoTime();
            String digest = burnCpu(units * costFactor);
            double processingMs = (System.nanoTime() - start) / 1_000_000d;
            served.incrementAndGet();

            out.println("DONE|" + nodeId + "|" + clock.tick() + "|"
                    + String.format("%.2f", processingMs) + ";" + digest
                    + ";" + pool.getActiveCount() + ";" + pool.getQueue().size());

        } catch (IOException e) {
            if (running) {
                System.err.println("Worker " + nodeId + " connection error: " + e.getMessage());
            }
        }
    }

    /**
     * Real CPU work. Repeated SHA-256 rounds, so a slower worker is slower because it
     * genuinely does more computation, not because it sleeps for longer.
     */
    private String burnCpu(int rounds) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = ("udcf-node-" + nodeId).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < rounds; i++) {
                buffer = digest.digest(buffer);
            }
            return HexFormat.of().formatHex(buffer, 0, 3);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public int nodeId()       { return nodeId; }
    public int port()         { return port; }
    public String label()     { return label; }
    public int costFactor()   { return costFactor; }
    public int poolSize()     { return pool.getCorePoolSize(); }
    public int served()       { return served.get(); }
    public int peakQueue()    { return peakQueue.get(); }
    public boolean isAlive()  { return alive; }
    public void resetCounters() { served.set(0); peakQueue.set(0); }
}
