package com.udcf.fault;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One node of the fault-tolerant primary-backup cluster.
 *
 * <p>Every node runs the same code. What differs is the role it currently holds, and
 * roles change by themselves: a backup that stops hearing from the primary promotes
 * itself, and a crashed primary that comes back demotes itself.</p>
 *
 * <h3>How split-brain is prevented</h3>
 * <p>The danger in any primary-backup system is two primaries accepting updates at once.
 * It happens when an old primary recovers still believing it is in charge. The fix used
 * here is an <b>epoch</b>: a counter that increases by one on every promotion.</p>
 * <ul>
 *   <li>Every update carries the epoch of the primary that accepted it.</li>
 *   <li>A backup refuses any update whose epoch is older than the newest it has seen.</li>
 *   <li>A recovering node asks its peers who the primary is, sees a higher epoch, and
 *       steps down instead of competing.</li>
 * </ul>
 * <p>So even if the old primary never realised it had been replaced, nobody would accept
 * its writes. The protection does not depend on the failed node behaving well.</p>
 */
public class FaultTolerantNode {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Object PRINT_LOCK = new Object();

    /** How often a backup checks the primary is still there. */
    private static final long HEARTBEAT_INTERVAL_MS = 300;

    /** Silence beyond this is treated as the primary having failed. */
    private static final long HEARTBEAT_TIMEOUT_MS = 1200;

    /** Connect and read timeout for every peer call. */
    private static final int PEER_TIMEOUT_MS = 800;

    /**
     * Delay applied to asynchronous replication only.
     *
     * <p>On one machine replication finishes in well under a millisecond, so a crash
     * would almost never catch an update in flight and the data-loss difference between
     * the two models would be invisible. This stands in for real network latency and is
     * stated openly rather than hidden.</p>
     */
    private static final long ASYNC_REPLICATION_DELAY_MS = 220;

    private final int nodeId;
    private final int port;
    private final int[] allIds;
    private final int[] allPorts;

    private final UpdateStore store = new UpdateStore();
    private final LamportClock clock = new LamportClock();
    private final ExecutorService replicationPool;

    private final AtomicReference<NodeRole> role = new AtomicReference<>(NodeRole.BACKUP);
    private final AtomicLong epoch = new AtomicLong(1);
    private final AtomicInteger primaryId = new AtomicInteger(-1);
    private final AtomicInteger updatesApplied = new AtomicInteger();

    private volatile long lastContactMillis = System.currentTimeMillis();
    private volatile boolean running = true;
    private volatile boolean alive = true;
    private volatile ConsistencyModel model = ConsistencyModel.SYNCHRONOUS;
    private volatile FailoverMetrics metrics;

    private ServerSocket serverSocket;

    public FaultTolerantNode(int nodeId, int port, int[] allIds, int[] allPorts) {
        this.nodeId = nodeId;
        this.port = port;
        this.allIds = allIds;
        this.allPorts = allPorts;
        this.replicationPool = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "node" + nodeId + "-replicator");
            t.setDaemon(true);
            return t;
        });
    }

    // ------------------------------------------------------------------ lifecycle

    public void start(NodeRole initialRole, int initialPrimary) throws IOException {
        role.set(initialRole);
        primaryId.set(initialPrimary);
        openSocket();

        Thread hb = new Thread(this::heartbeatLoop, "node" + nodeId + "-heartbeat");
        hb.setDaemon(true);
        hb.start();

        log("INIT", String.format("Node %d online on TCP %d as %s (epoch %d)",
                nodeId, port, initialRole, epoch.get()));
    }

    private void openSocket() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
        Thread accept = new Thread(this::acceptLoop, "node" + nodeId + "-accept");
        accept.setDaemon(true);
        accept.start();
    }

    /** Simulates a hard crash. The port closes, so peers get a refused connection. */
    public void crash() {
        alive = false;
        role.set(NodeRole.FAILED);
        closeSocket();
        log("CRASH", String.format(
                "*** Node %d has CRASHED - port closed, it answers nothing ***", nodeId));
    }

    /**
     * Restarts a crashed node.
     *
     * <p>It deliberately comes back believing it is still PRIMARY on its old epoch,
     * because that is what a real recovering process would believe. The demotion that
     * follows is the interesting part.</p>
     */
    public void recover() throws IOException {
        alive = true;
        running = true;
        lastContactMillis = System.currentTimeMillis();
        openSocket();
        log("RECOVER", String.format(
                "*** Node %d has RESTARTED and still believes it is PRIMARY on epoch %d ***",
                nodeId, epoch.get()));
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
            // Already closed; nothing to do.
        }
    }

    // ------------------------------------------------------------------ server

    private void acceptLoop() {
        while (running && alive) {
            try {
                Socket client = serverSocket.accept();
                final Socket accepted = client;
                Thread t = new Thread(() -> serve(accepted), "node" + nodeId + "-conn");
                t.setDaemon(true);
                t.start();
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
        String[] parts = raw.split("\\|", 5);
        MessageType type = MessageType.valueOf(parts[0]);
        int senderId = Integer.parseInt(parts[1]);
        long senderClock = Long.parseLong(parts[2]);
        long senderEpoch = Long.parseLong(parts[3]);
        String payload = parts.length > 4 ? parts[4] : "";

        clock.update(senderClock);

        switch (type) {
            case CLIENT_WRITE -> {
                if (role.get() != NodeRole.PRIMARY) {
                    return reply(MessageType.NOT_PRIMARY, String.valueOf(primaryId.get()));
                }
                return acceptClientWrite(payload);
            }

            case REPLICATE -> {
                SystemUpdate update = SystemUpdate.decode(payload);
                // The split-brain guard. An update from a superseded primary is refused
                // even though that primary has no idea it was superseded.
                if (update.epoch() < epoch.get()) {
                    log("REJECT", String.format(
                            "Refused update from Node %d: its epoch %d is older than ours (%d)"
                                    + " - this is split-brain prevention working",
                            senderId, update.epoch(), epoch.get()));
                    return reply(MessageType.STALE_EPOCH, String.valueOf(epoch.get()));
                }
                adoptEpoch(senderEpoch, senderId);
                boolean stored = store.apply(update);
                if (stored) {
                    updatesApplied.incrementAndGet();
                }
                lastContactMillis = System.currentTimeMillis();
                return reply(MessageType.ACK, update.sequence() + ";" + stored);
            }

            case HEARTBEAT -> {
                return reply(MessageType.PONG, role.get() + ";" + primaryId.get());
            }

            case ROLE_QUERY -> {
                return reply(MessageType.ROLE_REPLY,
                        role.get() + ";" + epoch.get() + ";" + primaryId.get());
            }

            case NEW_PRIMARY -> {
                String[] p = payload.split(";");
                int newPrimary = Integer.parseInt(p[0]);
                long newEpoch = Long.parseLong(p[1]);
                if (newEpoch >= epoch.get()) {
                    boolean wasPrimary = role.get() == NodeRole.PRIMARY;
                    epoch.set(newEpoch);
                    primaryId.set(newPrimary);
                    role.set(newPrimary == nodeId ? NodeRole.PRIMARY : NodeRole.BACKUP);
                    lastContactMillis = System.currentTimeMillis();
                    log("ROLE", String.format(
                            "Node %d accepts Node %d as PRIMARY on epoch %d%s",
                            nodeId, newPrimary, newEpoch,
                            wasPrimary && newPrimary != nodeId ? "  - DEMOTED to backup" : ""));
                }
                return reply(MessageType.ACK, "ok");
            }

            case SYNC_REQUEST -> {
                return reply(MessageType.SYNC_REPLY, store.encodeAll());
            }

            default -> {
                return reply(MessageType.ACK, "unsupported");
            }
        }
    }

    private String reply(MessageType type, String payload) {
        return type.name() + "|" + nodeId + "|" + clock.tick() + "|" + epoch.get() + "|" + payload;
    }

    // ------------------------------------------------------------------ primary path

    /** Applies a client update locally, then replicates according to the chosen model. */
    private String acceptClientWrite(String payload) {
        String[] p = payload.split(";", 3);
        int sequence = Integer.parseInt(p[0]);
        SystemUpdate update = new SystemUpdate(sequence, p[1], p[2],
                clock.tick(), nodeId, epoch.get());

        store.apply(update);
        updatesApplied.incrementAndGet();

        if (model == ConsistencyModel.SYNCHRONOUS) {
            // Confirm only once the update is safely on another machine.
            for (int peer : peers()) {
                replicateTo(peer, update, false);
            }
        } else {
            // Confirm now; the backups catch up afterwards. Anything still travelling
            // when this node dies is lost, even though the client was told it succeeded.
            for (int peer : peers()) {
                replicationPool.submit(() -> replicateTo(peer, update, true));
            }
        }
        return reply(MessageType.ACK, update.sequence() + ";stored");
    }

    private void replicateTo(int peerId, SystemUpdate update, boolean delayed) {
        if (delayed) {
            sleep(ASYNC_REPLICATION_DELAY_MS);
        }
        try {
            send(peerId, MessageType.REPLICATE, update.encode());
        } catch (IOException e) {
            // Backup unreachable. The primary keeps serving the rest of the cluster.
        }
    }

    // ------------------------------------------------------------------ failure detection

    private void heartbeatLoop() {
        while (running) {
            sleep(HEARTBEAT_INTERVAL_MS);
            if (!alive || role.get() == NodeRole.PRIMARY) {
                continue;
            }
            int primary = primaryId.get();
            if (primary == -1 || primary == nodeId) {
                continue;
            }
            try {
                send(primary, MessageType.HEARTBEAT, "");
                lastContactMillis = System.currentTimeMillis();
            } catch (IOException e) {
                if (System.currentTimeMillis() - lastContactMillis > HEARTBEAT_TIMEOUT_MS) {
                    onPrimarySuspected(primary);
                }
            }
        }
    }

    /**
     * Decides whether to take over.
     *
     * <p>The rule is the one from Experiment 4: the highest surviving identifier wins. The
     * node probes everyone above it, and promotes itself only if none of them answers.
     * That keeps exactly one node promoting without needing a full election round.</p>
     */
    private void onPrimarySuspected(int deadPrimary) {
        if (role.get() != NodeRole.BACKUP) {
            return;
        }
        if (metrics != null) {
            metrics.markDetected();
        }
        log("DETECT", String.format(
                "!!! Node %d has not heard from PRIMARY Node %d for %d ms - suspecting failure !!!",
                nodeId, deadPrimary, HEARTBEAT_TIMEOUT_MS));

        for (int id : allIds) {
            if (id > nodeId && id != deadPrimary && isReachable(id)) {
                log("STAND", String.format(
                        "Node %d stands down - Node %d is alive and outranks it", nodeId, id));
                return;
            }
        }
        promoteSelf();
    }

    /** Takes over as primary, raising the epoch so the old primary can never return. */
    private void promoteSelf() {
        if (!role.compareAndSet(NodeRole.BACKUP, NodeRole.PRIMARY)) {
            return;
        }
        long newEpoch = epoch.incrementAndGet();
        primaryId.set(nodeId);

        log("PROMOTE", String.format(
                "*** Node %d PROMOTES ITSELF to PRIMARY, epoch raised to %d ***", nodeId, newEpoch));
        if (metrics != null) {
            metrics.markPromoted(nodeId, newEpoch);
        }

        for (int peer : peers()) {
            try {
                send(peer, MessageType.NEW_PRIMARY, nodeId + ";" + newEpoch);
            } catch (IOException e) {
                // That peer is down too; it will learn the epoch when it recovers.
            }
        }
    }

    /**
     * Called after a crashed node restarts. It asks the cluster who is in charge now.
     *
     * <p>If anybody reports a higher epoch, this node steps down and copies the current
     * state. A recovering primary must never resume writing on its old authority.</p>
     */
    public void rejoinCluster() {
        long highestEpoch = epoch.get();
        int currentPrimary = primaryId.get();

        for (int peer : peers()) {
            try {
                String response = send(peer, MessageType.ROLE_QUERY, "");
                String[] parts = response.split("\\|", 5);
                String[] body = parts[4].split(";");
                long peerEpoch = Long.parseLong(body[1]);
                int peerPrimary = Integer.parseInt(body[2]);
                if (peerEpoch > highestEpoch) {
                    highestEpoch = peerEpoch;
                    currentPrimary = peerPrimary;
                }
            } catch (IOException e) {
                // Peer unreachable; ask the next one.
            }
        }

        if (highestEpoch > epoch.get()) {
            epoch.set(highestEpoch);
            primaryId.set(currentPrimary);
            role.set(NodeRole.BACKUP);
            log("DEMOTE", String.format(
                    "Node %d discovers epoch %d with PRIMARY Node %d - it was replaced while"
                            + " down, so it DEMOTES itself to backup",
                    nodeId, highestEpoch, currentPrimary));
            resyncFrom(currentPrimary);
        } else {
            log("ROLE", String.format("Node %d found no newer primary and stays PRIMARY", nodeId));
        }
    }

    /** Copies the current primary's whole state, keeping only what is genuinely newer. */
    private void resyncFrom(int primary) {
        try {
            String response = send(primary, MessageType.SYNC_REQUEST, "");
            String[] parts = response.split("\\|", 5);
            int before = store.size();
            if (parts.length > 4 && !parts[4].isBlank()) {
                for (String encoded : parts[4].split("~")) {
                    store.apply(SystemUpdate.decode(encoded));
                }
            }
            log("RESYNC", String.format(
                    "Node %d copied state from PRIMARY Node %d: %d items before, %d after",
                    nodeId, primary, before, store.size()));
        } catch (IOException e) {
            log("RESYNC", "Could not reach the primary to resynchronise");
        }
    }

    // ------------------------------------------------------------------ helpers

    private void adoptEpoch(long senderEpoch, int senderId) {
        if (senderEpoch > epoch.get()) {
            epoch.set(senderEpoch);
            primaryId.set(senderId);
            if (role.get() == NodeRole.PRIMARY) {
                role.set(NodeRole.BACKUP);
                log("DEMOTE", String.format(
                        "Node %d saw a newer epoch %d from Node %d and demoted itself",
                        nodeId, senderEpoch, senderId));
            }
        }
    }

    private boolean isReachable(int id) {
        try {
            send(id, MessageType.HEARTBEAT, "");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private List<Integer> peers() {
        List<Integer> list = new ArrayList<>();
        for (int id : allIds) {
            if (id != nodeId) {
                list.add(id);
            }
        }
        return list;
    }

    String send(int targetId, MessageType type, String payload) throws IOException {
        long stamped = clock.tick();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", portOf(targetId)), PEER_TIMEOUT_MS);
            socket.setSoTimeout(PEER_TIMEOUT_MS);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.println(type.name() + "|" + nodeId + "|" + stamped + "|" + epoch.get() + "|" + payload);
            String response = in.readLine();
            if (response == null) {
                throw new IOException("no reply from node " + targetId);
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
        throw new IllegalArgumentException("unknown node " + id);
    }

    private static void sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void log(String category, String message) {
        synchronized (PRINT_LOCK) {
            System.out.printf("[%s] [Node %d | %-7s | epoch %d] [%-8s] %s%n",
                    LocalTime.now().format(TIME_FMT), nodeId, role.get(), epoch.get(),
                    category, message);
        }
    }

    public int nodeId()                 { return nodeId; }
    public int port()                   { return port; }
    public NodeRole role()              { return role.get(); }
    public long epoch()                 { return epoch.get(); }
    public int believedPrimary()        { return primaryId.get(); }
    public boolean isAlive()            { return alive; }
    public UpdateStore store()          { return store; }
    public int updatesApplied()         { return updatesApplied.get(); }
    public void setModel(ConsistencyModel m)      { this.model = m; }
    public void setMetrics(FailoverMetrics m)     { this.metrics = m; }
}
