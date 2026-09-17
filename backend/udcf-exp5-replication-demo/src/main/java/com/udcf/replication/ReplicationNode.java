package com.udcf.replication;

import com.udcf.sync.LamportClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * One node of the primary-backup replicated store, communicating over TCP.
 *
 * <p>A node is either the PRIMARY, which accepts client writes and pushes them outward,
 * or a BACKUP, which applies whatever the primary sends. Every node runs a TCP server
 * socket so it can receive replication, read and dump requests concurrently.</p>
 *
 * <p><b>Why TCP here and UDP in Experiment 4.</b> Leader election needed an unreliable
 * transport, because the whole point was inferring failure from silence. Replication
 * needs the opposite: the primary must know with certainty whether a backup stored the
 * update, so it needs a real acknowledgement. TCP gives guaranteed, ordered delivery, and
 * a connection to a crashed backup is refused immediately instead of vanishing silently.</p>
 *
 * <p>Asynchronous replication runs on an {@link ExecutorService}, which is the same
 * thread-pool mechanism built in Experiment 2 — replication work is just another task
 * submitted to the node's pool.</p>
 */
public class ReplicationNode {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Object PRINT_LOCK = new Object();

    /** How long the primary waits for a backup to accept a connection and reply. */
    private static final int REPLICATION_TIMEOUT_MS = 1500;

    /**
     * Artificial delay applied to asynchronous replication only.
     *
     * <p>On one machine a replication round trip completes in well under a millisecond,
     * which would make the eventual-consistency window far too short to observe. This
     * delay stands in for the wide-area network latency a real deployment would have. It
     * is stated openly rather than hidden: without it the demonstration would show
     * nothing, and claiming a visible stale window on localhost would be false.</p>
     */
    private static final long ASYNC_NETWORK_DELAY_MS = 450;

    private final int nodeId;
    private final int port;
    private final NodeRole role;
    private final int[] backupIds;
    private final int[] allIds;
    private final int[] allPorts;

    private final LamportClock clock = new LamportClock();
    private final DataStore store = new DataStore();
    private final ReplicationEventLog eventLog;
    private final Map<Integer, ReplicationStats> stats = new LinkedHashMap<>();

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final ExecutorService replicationPool;

    private volatile boolean running = true;
    private volatile boolean alive = true;

    public ReplicationNode(int nodeId, int port, NodeRole role,
                           int[] backupIds, int[] allIds, int[] allPorts,
                           ReplicationEventLog eventLog) {
        this.nodeId = nodeId;
        this.port = port;
        this.role = role;
        this.backupIds = backupIds;
        this.allIds = allIds;
        this.allPorts = allPorts;
        this.eventLog = eventLog;
        this.replicationPool = Executors.newFixedThreadPool(Math.max(2, backupIds.length),
                r -> {
                    Thread t = new Thread(r, "node" + nodeId + "-replicator");
                    t.setDaemon(true);
                    return t;
                });
        for (int id : backupIds) {
            stats.put(id, new ReplicationStats(id));
        }
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() throws IOException {
        openSocket();
        log("INIT", clock.current(), 0, String.format(
                "Node %d online on TCP port %d as %s", nodeId, port, role));
    }

    private void openSocket() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
        acceptThread = new Thread(this::acceptLoop, "node" + nodeId + "-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /**
     * Simulates a crash by closing the listening socket.
     *
     * <p>Connections from the primary are then refused by the operating system, so the
     * primary discovers the failure as a real IOException on connect rather than as a
     * flag someone set. That is exactly how a crashed replica behaves in practice.</p>
     */
    public void crash() {
        alive = false;
        closeSocket();
        long t = clock.tick();
        log("CRASH", t, 0, String.format(
                "*** Node %d has CRASHED - its TCP port is closed, connections will be refused ***",
                nodeId));
    }

    /** Restarts the listener. The node comes back with whatever it had before the crash. */
    public void recover() throws IOException {
        alive = true;
        running = true;
        openSocket();
        long t = clock.tick();
        log("RECOVER", t, 0, String.format(
                "*** Node %d has RECOVERED with %d items - it is now behind the primary ***",
                nodeId, store.size()));
    }

    public void shutdown() {
        running = false;
        closeSocket();
        replicationPool.shutdownNow();
    }

    private void closeSocket() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // Closing an already-closed socket is not an error worth reporting.
        }
    }

    // ------------------------------------------------------------------ server side

    private void acceptLoop() {
        while (running && alive) {
            try {
                Socket client = serverSocket.accept();
                Thread t = new Thread(() -> serve(client), "node" + nodeId + "-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;   // socket closed by crash() or shutdown(); expected
            }
        }
    }

    private void serve(Socket client) {
        try (Socket c = client;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(c.getOutputStream(), true)) {
            String line = in.readLine();
            if (line != null) {
                out.println(handle(line));
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Node " + nodeId + " connection error: " + e.getMessage());
            }
        }
    }

    private String handle(String raw) {
        String[] parts = raw.split("\\|", 4);
        MessageType type = MessageType.valueOf(parts[0]);
        int senderId = Integer.parseInt(parts[1]);
        long senderClock = Long.parseLong(parts[2]);
        String payload = parts.length > 3 ? parts[3] : "";

        long updated = clock.update(senderClock);   // Lamport Rule 3

        switch (type) {
            case REPLICATE -> {
                DataItem item = DataItem.decode(payload);
                boolean stored = store.apply(item);
                if (stored) {
                    log("APPLY", updated, senderId, String.format(
                            "Node %d stored %s from the primary", nodeId, item.shortForm()));
                } else {
                    log("STALE", updated, senderId, String.format(
                            "Node %d REJECTED %s - it already holds a newer version",
                            nodeId, item.shortForm()));
                }
                return reply(MessageType.ACK, item.key() + ";" + stored);
            }
            case READ -> {
                Optional<DataItem> found = store.get(payload);
                String value = found.map(DataItem::shortForm).orElse("<absent>");
                return reply(MessageType.READ_REPLY, payload + ";" + value);
            }
            case DUMP -> {
                return reply(MessageType.DUMP_REPLY, store.encodeAll());
            }
            default -> {
                return reply(MessageType.ACK, "unsupported");
            }
        }
    }

    private String reply(MessageType type, String payload) {
        long stamped = clock.tick();
        return type.name() + "|" + nodeId + "|" + stamped + "|" + payload;
    }

    // ------------------------------------------------------------------ primary side

    /**
     * Client write, applied locally and then replicated according to the chosen model.
     *
     * @return the milliseconds the client waited before being told the write succeeded
     */
    public double write(String key, String value, ConsistencyModel model) {
        long start = System.nanoTime();
        long stamped = clock.tick();
        DataItem item = new DataItem(key, value, stamped, nodeId);
        store.apply(item);

        log("WRITE", stamped, 0, String.format(
                "Client write %s  [%s]", item.shortForm(), model));

        if (model == ConsistencyModel.SYNCHRONOUS) {
            // The client is not told the write succeeded until every backup has replied.
            for (int backupId : backupIds) {
                replicateTo(backupId, item, false);
            }
        } else {
            // The client is released now; the backups catch up on the pool.
            for (int backupId : backupIds) {
                replicationPool.submit(() -> replicateTo(backupId, item, true));
            }
        }

        double waited = (System.nanoTime() - start) / 1_000_000d;
        log("DONE", clock.current(), 0, String.format(
                "Write of '%s' confirmed to client after %.1f ms  [%s]",
                key, waited, model));
        return waited;
    }

    /** Pushes one item to one backup and waits for its acknowledgement. */
    private void replicateTo(int backupId, DataItem item, boolean simulateNetworkDelay) {
        if (simulateNetworkDelay) {
            sleep(ASYNC_NETWORK_DELAY_MS);
        }
        ReplicationStats st = stats.get(backupId);
        long start = System.nanoTime();
        try {
            String response = request(backupId, MessageType.REPLICATE, item.encode());
            double latency = (System.nanoTime() - start) / 1_000_000d;

            String[] parts = response.split("\\|", 4);
            clock.update(Long.parseLong(parts[2]));
            boolean stored = parts[3].endsWith("true");

            if (stored) {
                st.recordSuccess(latency);
                log("ACK", clock.current(), backupId, String.format(
                        "Node %d acknowledged '%s' in %.1f ms", backupId, item.key(), latency));
            } else {
                st.recordStaleRejection();
                log("STALE", clock.current(), backupId, String.format(
                        "Node %d rejected '%s' as stale - no overwrite happened",
                        backupId, item.key()));
            }
        } catch (IOException e) {
            st.recordFailure();
            long t = clock.tick();
            log("FAIL", t, backupId, String.format(
                    "!!! Replication of '%s' to Node %d FAILED: %s !!!",
                    item.key(), backupId, e.getClass().getSimpleName()));
        }
    }

    /**
     * Anti-entropy: pushes every item the primary holds to one backup.
     *
     * <p>Used after a backup recovers. The backup's own last-writer-wins rule means items
     * it already has at the same version are rejected harmlessly, so only the updates it
     * genuinely missed are stored. No bookkeeping of "what did it miss" is required.</p>
     */
    public void resync(int backupId) {
        long t = clock.tick();
        log("RESYNC", t, backupId, String.format(
                "Anti-entropy: pushing all %d items to recovered Node %d",
                store.size(), backupId));
        for (DataItem item : store.snapshot().values()) {
            replicateTo(backupId, item, false);
        }
    }

    /**
     * Sends a pre-built item straight to one backup, bypassing the normal write path.
     *
     * <p>This exists only so the demonstration can deliver an update out of order and show
     * the backup refusing it. It is not part of the normal replication flow.</p>
     */
    public void injectOutOfOrderUpdate(int backupId, DataItem staleItem) {
        log("INJECT", clock.current(), backupId, String.format(
                "Delivering %s to Node %d out of order, after a newer version",
                staleItem.shortForm(), backupId));
        replicateTo(backupId, staleItem, false);
    }

    // ------------------------------------------------------------------ reads

    /** Reads a key from a specific replica, so stale reads can be observed directly. */
    public String readFrom(int targetId, String key) {
        if (targetId == nodeId) {
            return store.get(key).map(DataItem::shortForm).orElse("<absent>");
        }
        try {
            String response = request(targetId, MessageType.READ, key);
            String[] parts = response.split("\\|", 4);
            clock.update(Long.parseLong(parts[2]));
            return parts[3].substring(parts[3].indexOf(';') + 1);
        } catch (IOException e) {
            return "<unreachable>";
        }
    }

    /** Fetches a replica's whole store, used for the final consistency comparison. */
    public Map<String, DataItem> fetchStore(int targetId) {
        if (targetId == nodeId) {
            return store.snapshot();
        }
        Map<String, DataItem> result = new LinkedHashMap<>();
        try {
            String response = request(targetId, MessageType.DUMP, "");
            String[] parts = response.split("\\|", 4);
            if (parts.length > 3 && !parts[3].isBlank()) {
                for (String encoded : parts[3].split("~")) {
                    DataItem item = DataItem.decode(encoded);
                    result.put(item.key(), item);
                }
            }
        } catch (IOException e) {
            return null;   // null distinguishes "unreachable" from "empty"
        }
        return result;
    }

    // ------------------------------------------------------------------ transport

    /** One TCP request-response exchange. Applies Lamport Rule 2 before sending. */
    private String request(int targetId, MessageType type, String payload) throws IOException {
        long stamped = clock.tick();
        String message = type.name() + "|" + nodeId + "|" + stamped + "|" + payload;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", portOf(targetId)),
                    REPLICATION_TIMEOUT_MS);
            socket.setSoTimeout(REPLICATION_TIMEOUT_MS);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.println(message);
            String response = in.readLine();
            if (response == null) {
                throw new IOException("no response from node " + targetId);
            }
            return response;
        }
    }

    private int portOf(int id) {
        for (int i = 0; i < allIds.length; i++) {
            if (allIds[i] == id) {
                return allPorts[i];
            }
        }
        throw new IllegalArgumentException("unknown node id " + id);
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ logging

    private void log(String category, long lamportTime, int peerId, String description) {
        LocalTime now = LocalTime.now();
        synchronized (PRINT_LOCK) {
            System.out.printf("[%s] [Node %d | Lamport: %3d] [%-8s] %s%n",
                    now.format(TIME_FMT), nodeId, lamportTime, category, description);
        }
        if (!category.equals("INIT")) {
            eventLog.record(new ReplicationEvent(
                    nodeId, category, lamportTime, peerId, description, now));
        }
    }

    public int getNodeId()                        { return nodeId; }
    public NodeRole getRole()                     { return role; }
    public boolean isAlive()                      { return alive; }
    public long getClockValue()                   { return clock.current(); }
    public DataStore getStore()                   { return store; }
    public Map<Integer, ReplicationStats> stats() { return stats; }
}
