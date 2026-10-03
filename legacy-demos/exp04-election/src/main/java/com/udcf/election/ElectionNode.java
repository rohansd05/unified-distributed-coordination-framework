package com.udcf.election;

import com.udcf.sync.LamportClock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One node of the UDCF cluster, implementing both the Bully and the Ring election
 * algorithms over real UDP sockets.
 *
 * <p>Each node runs three things concurrently, mirroring the deployed framework:</p>
 * <ul>
 *   <li>a <b>listener thread</b> that receives and dispatches election traffic,</li>
 *   <li>a <b>heartbeat thread</b> that pings the current coordinator and declares it
 *       failed when replies stop arriving,</li>
 *   <li><b>election threads</b> spawned on demand, so a running election never blocks
 *       the listener from answering incoming messages.</li>
 * </ul>
 *
 * <p>A node "crashes" by setting {@code alive} to false, after which it silently drops
 * every datagram it receives. This is a faithful simulation: from the perspective of the
 * other nodes, a crashed process and an unreachable process are indistinguishable, which
 * is precisely why election algorithms rely on timeouts rather than on notifications.</p>
 *
 * <p>Every message carries the sender's Lamport timestamp, so the merged election log can
 * be replayed in causal order. That is the integration point with Experiment 3.</p>
 */
public class ElectionNode {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** Serialises console output so three threads never split a line. */
    private static final Object PRINT_LOCK = new Object();

    /** How long a candidate waits for an OK from a higher node before winning. */
    private static final long OK_TIMEOUT_MS = 900;

    /** How long a suppressed candidate waits for the winner's announcement. */
    private static final long COORDINATOR_TIMEOUT_MS = 2200;

    /** Silence from the coordinator beyond this is treated as failure. */
    private static final long HEARTBEAT_TIMEOUT_MS = 2500;

    private static final long HEARTBEAT_INTERVAL_MS = 700;

    /** How long a ring node waits for a successor to answer a liveness probe. */
    private static final long PROBE_TIMEOUT_MS = 300;

    private final int nodeId;
    private final int port;
    private final int[] allIds;
    private final int[] allPorts;

    private final LamportClock clock = new LamportClock();
    private final ElectionEventLog eventLog;

    private DatagramSocket socket;
    private Thread listenerThread;
    private Thread heartbeatThread;

    private volatile boolean running = true;
    private volatile boolean alive = true;

    private final AtomicInteger coordinatorId = new AtomicInteger(-1);
    private final AtomicBoolean electionInProgress = new AtomicBoolean(false);

    private volatile boolean okReceived;
    private volatile boolean coordinatorAnnounced;
    private volatile long lastPongMillis;
    private volatile long electionStartNanos;

    private final Set<Integer> probeAcks = ConcurrentHashMap.newKeySet();

    public ElectionNode(int nodeId, int port, int[] allIds, int[] allPorts,
                        ElectionEventLog eventLog) {
        this.nodeId = nodeId;
        this.port = port;
        this.allIds = allIds;
        this.allPorts = allPorts;
        this.eventLog = eventLog;
    }

    // ---------------------------------------------------------------- lifecycle

    public void start() throws Exception {
        socket = new DatagramSocket(port);
        socket.setSoTimeout(400);

        listenerThread = new Thread(this::listenLoop, "node" + nodeId + "-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();

        heartbeatThread = new Thread(this::heartbeatLoop, "node" + nodeId + "-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();

        log("INIT", clock.current(), 0,
                String.format("Node %d online on UDP port %d, no coordinator known yet",
                        nodeId, port));
    }

    public void shutdown() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    /** Simulates a process crash: the node stops responding to everything. */
    public void crash() {
        alive = false;
        long t = clock.tick();
        log("CRASH", t, 0, String.format(
                "*** Node %d has CRASHED - it will now silently drop every message ***", nodeId));
    }

    /** Simulates a restart. A recovered highest-ID node will reclaim leadership. */
    public void recover() {
        alive = true;
        lastPongMillis = System.currentTimeMillis();
        coordinatorId.set(-1);
        long t = clock.tick();
        log("RECOVER", t, 0, String.format(
                "*** Node %d has RECOVERED and rejoined the cluster ***", nodeId));
    }

    // ---------------------------------------------------------------- BULLY

    /**
     * Bully algorithm, run on its own thread.
     *
     * <p>The node challenges every higher-numbered node. If none answers within the
     * timeout it declares itself coordinator. If one does answer, it steps aside and
     * waits to be told the result — restarting the election if that announcement never
     * arrives, which is what makes the algorithm tolerate a second failure mid-election.</p>
     */
    public void startBullyElection() {
        if (!alive) {
            return;
        }
        if (!electionInProgress.compareAndSet(false, true)) {
            return;   // an election is already running on this node
        }

        try {
            electionStartNanos = System.nanoTime();
            okReceived = false;
            coordinatorAnnounced = false;

            long t = clock.tick();
            List<Integer> higher = higherNodes();

            log("ELECTION", t, 0, String.format(
                    "BULLY: Node %d starts an election, challenging higher nodes %s",
                    nodeId, higher.isEmpty() ? "(none)" : higher.toString()));

            if (higher.isEmpty()) {
                declareCoordinator("no higher node exists");
                return;
            }

            for (int target : higher) {
                long ts = send(target, MessageType.ELECTION, "");
                log("SEND", ts, target, String.format(
                        "BULLY: ELECTION  Node %d -> Node %d", nodeId, target));
            }

            Thread.sleep(OK_TIMEOUT_MS);

            if (!okReceived) {
                declareCoordinator("no OK received within " + OK_TIMEOUT_MS + " ms");
                return;
            }

            long tw = clock.tick();
            log("WAIT", tw, 0, String.format(
                    "BULLY: Node %d received OK, standing down and awaiting COORDINATOR", nodeId));

            Thread.sleep(COORDINATOR_TIMEOUT_MS);

            if (!coordinatorAnnounced && alive) {
                long tr = clock.tick();
                log("RETRY", tr, 0, String.format(
                        "BULLY: Node %d saw no COORDINATOR in time - restarting election", nodeId));
                electionInProgress.set(false);
                startBullyElection();
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            electionInProgress.set(false);
        }
    }

    private void declareCoordinator(String reason) {
        coordinatorId.set(nodeId);
        coordinatorAnnounced = true;
        double durationMs = (System.nanoTime() - electionStartNanos) / 1_000_000d;

        long t = clock.tick();
        log("ELECTED", t, 0, String.format(
                "*** Node %d becomes COORDINATOR (%s) - election took %.1f ms ***",
                nodeId, reason, durationMs));

        for (int target : allIds) {
            if (target == nodeId) {
                continue;
            }
            long ts = send(target, MessageType.COORDINATOR, String.valueOf(nodeId));
            log("SEND", ts, target, String.format(
                    "BULLY: COORDINATOR announcement Node %d -> Node %d", nodeId, target));
        }
    }

    // ---------------------------------------------------------------- RING

    /**
     * Ring algorithm, run on its own thread.
     *
     * <p>The election token carries the list of node IDs it has visited. When the token
     * returns to the node that started it, the highest ID in the list wins. A liveness
     * probe before each hop lets a dead successor be skipped, which is what stops the
     * token from being lost at a crashed node.</p>
     */
    public void startRingElection() {
        if (!alive) {
            return;
        }
        electionStartNanos = System.nanoTime();
        long t = clock.tick();
        log("RING", t, 0, String.format(
                "RING: Node %d starts a ring election, token = [%d]", nodeId, nodeId));
        forwardRing(MessageType.RING_ELECTION, String.valueOf(nodeId));
    }

    /**
     * Sends to the first live successor around the ring.
     * Returns the node it reached, or -1 if the whole ring is unreachable.
     */
    private int forwardRing(MessageType type, String payload) {
        int myIndex = indexOf(nodeId);
        for (int offset = 1; offset < allIds.length; offset++) {
            int candidate = allIds[(myIndex + offset) % allIds.length];
            if (probeAlive(candidate)) {
                long ts = send(candidate, type, payload);
                log("SEND", ts, candidate, String.format(
                        "RING: %-16s Node %d -> Node %d   token=[%s]",
                        type.name(), nodeId, candidate, payload));
                return candidate;
            }
            long ts = clock.tick();
            log("SKIP", ts, candidate, String.format(
                    "RING: Node %d did not answer the probe - skipping to the next successor",
                    candidate));
        }
        return -1;
    }

    /** Liveness check: a crashed node never replies, so the probe simply times out. */
    private boolean probeAlive(int targetId) {
        probeAcks.remove(targetId);
        send(targetId, MessageType.PROBE, "");
        long deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (probeAcks.contains(targetId)) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- messaging

    private void listenLoop() {
        byte[] buffer = new byte[2048];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String raw = new String(packet.getData(), 0, packet.getLength(),
                        StandardCharsets.UTF_8);
                handle(raw);
            } catch (SocketTimeoutException ignored) {
                // Expected: lets the loop re-check the running flag.
            } catch (Exception e) {
                if (running) {
                    System.err.println("Node " + nodeId + " listener error: " + e.getMessage());
                }
            }
        }
    }

    private void handle(String raw) {
        String[] parts = raw.split("\\|", 4);
        MessageType type = MessageType.valueOf(parts[0]);
        int senderId = Integer.parseInt(parts[1]);
        long senderClock = Long.parseLong(parts[2]);
        String payload = parts.length > 3 ? parts[3] : "";

        // A crashed node drops everything. This is what makes failure detection real:
        // the sender learns nothing except that no reply arrived.
        if (!alive) {
            return;
        }

        long updated = clock.update(senderClock);   // Lamport Rule 3

        switch (type) {
            case ELECTION -> {
                log("RECV", updated, senderId, String.format(
                        "BULLY: ELECTION from Node %d - replying OK and starting own election",
                        senderId));
                long ts = send(senderId, MessageType.OK, "");
                log("SEND", ts, senderId, String.format(
                        "BULLY: OK        Node %d -> Node %d", nodeId, senderId));
                if (!electionInProgress.get()) {
                    new Thread(this::startBullyElection, "node" + nodeId + "-election").start();
                }
            }
            case OK -> {
                okReceived = true;
                log("RECV", updated, senderId, String.format(
                        "BULLY: OK from Node %d - Node %d is outranked and stands down",
                        senderId, nodeId));
            }
            case COORDINATOR -> {
                int winner = Integer.parseInt(payload);
                coordinatorId.set(winner);
                coordinatorAnnounced = true;
                lastPongMillis = System.currentTimeMillis();
                log("COORD", updated, senderId, String.format(
                        "Node %d accepts Node %d as the new COORDINATOR", nodeId, winner));
            }
            // Ring handling blocks on a liveness probe, and only this listener thread
            // can deliver the PROBE_ACK that unblocks it. Handling ring messages inline
            // would therefore deadlock the node, so each is dispatched to its own thread.
            case RING_ELECTION -> new Thread(
                    () -> handleRingElection(updated, senderId, payload),
                    "node" + nodeId + "-ring").start();
            case RING_COORDINATOR -> new Thread(
                    () -> handleRingCoordinator(updated, senderId, payload),
                    "node" + nodeId + "-ring").start();
            case PING -> send(senderId, MessageType.PONG, "");
            case PONG -> lastPongMillis = System.currentTimeMillis();
            case PROBE -> send(senderId, MessageType.PROBE_ACK, "");
            case PROBE_ACK -> probeAcks.add(senderId);
        }
    }

    private void handleRingElection(long updated, int senderId, String payload) {
        List<Integer> token = parseToken(payload);

        if (token.contains(nodeId)) {
            int winner = token.stream().mapToInt(Integer::intValue).max().orElse(nodeId);
            double durationMs = (System.nanoTime() - electionStartNanos) / 1_000_000d;

            coordinatorId.set(winner);
            log("ELECTED", updated, senderId, String.format(
                    "*** RING: token returned to Node %d with %s - highest ID %d wins"
                            + " (%.1f ms) ***",
                    nodeId, token, winner, durationMs));

            forwardRing(MessageType.RING_COORDINATOR, winner + ":" + nodeId);
            return;
        }

        token.add(nodeId);
        String forwarded = joinToken(token);
        log("RING", updated, senderId, String.format(
                "RING: Node %d appends itself, token becomes [%s]", nodeId, forwarded));
        forwardRing(MessageType.RING_ELECTION, forwarded);
    }

    private void handleRingCoordinator(long updated, int senderId, String payload) {
        String[] halves = payload.split(":");
        int winner = Integer.parseInt(halves[0]);
        int originator = Integer.parseInt(halves[1]);

        if (originator == nodeId) {
            log("COORD", updated, senderId, String.format(
                    "RING: result token has circulated the whole ring - Node %d is COORDINATOR",
                    winner));
            return;
        }

        coordinatorId.set(winner);
        lastPongMillis = System.currentTimeMillis();
        log("COORD", updated, senderId, String.format(
                "RING: Node %d accepts Node %d as COORDINATOR and forwards the result",
                nodeId, winner));
        forwardRing(MessageType.RING_COORDINATOR, payload);
    }

    private void heartbeatLoop() {
        while (running) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
                if (!alive) {
                    continue;
                }
                int coord = coordinatorId.get();
                if (coord == -1 || coord == nodeId) {
                    continue;
                }
                send(coord, MessageType.PING, "");

                if (System.currentTimeMillis() - lastPongMillis > HEARTBEAT_TIMEOUT_MS) {
                    long t = clock.tick();
                    log("DETECT", t, coord, String.format(
                            "!!! Node %d detected that COORDINATOR Node %d is unreachable"
                                    + " (no PONG for %d ms) !!!",
                            nodeId, coord, HEARTBEAT_TIMEOUT_MS));
                    coordinatorId.set(-1);
                    new Thread(this::startBullyElection, "node" + nodeId + "-election").start();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Applies Lamport Rule 2 and transmits, returning the timestamp actually sent.
     *
     * <p>The tick happens inside this method rather than at the call site so that the
     * value logged and the value transmitted can never diverge — with several threads
     * advancing the same clock, ticking separately would allow another thread to slip
     * an increment in between.</p>
     */
    private long send(int targetId, MessageType type, String payload) {
        long stamped = clock.tick();
        try {
            int targetPort = allPorts[indexOf(targetId)];
            String message = type.name() + "|" + nodeId + "|" + stamped + "|" + payload;
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length,
                    InetAddress.getByName("127.0.0.1"), targetPort));
        } catch (Exception e) {
            // A send failure is indistinguishable from a crashed peer, which is exactly
            // the uncertainty these algorithms are designed to tolerate.
        }
        return stamped;
    }

    // ---------------------------------------------------------------- helpers

    private List<Integer> higherNodes() {
        List<Integer> higher = new ArrayList<>();
        for (int id : allIds) {
            if (id > nodeId) {
                higher.add(id);
            }
        }
        return higher;
    }

    private int indexOf(int id) {
        for (int i = 0; i < allIds.length; i++) {
            if (allIds[i] == id) {
                return i;
            }
        }
        return 0;
    }

    private List<Integer> parseToken(String payload) {
        List<Integer> token = new ArrayList<>();
        for (String part : payload.split(",")) {
            if (!part.isBlank()) {
                token.add(Integer.parseInt(part.trim()));
            }
        }
        return token;
    }

    private String joinToken(List<Integer> token) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < token.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(token.get(i));
        }
        return sb.toString();
    }

    private void log(String category, long lamportTime, int peerId, String description) {
        LocalTime now = LocalTime.now();
        synchronized (PRINT_LOCK) {
            System.out.printf("[%s] [Node %d | Lamport: %3d] [%-8s] %s%n",
                    now.format(TIME_FMT), nodeId, lamportTime, category, description);
        }
        if (!category.equals("INIT")) {
            eventLog.record(new ElectionEvent(
                    nodeId, category, lamportTime, peerId, description, now));
        }
    }

    public int getNodeId()          { return nodeId; }
    public int getCoordinatorId()   { return coordinatorId.get(); }
    public boolean isAlive()        { return alive; }
    public long getClockValue()     { return clock.current(); }

    @Override
    public String toString() {
        return "Node " + nodeId + " (port " + port + ")";
    }
}
