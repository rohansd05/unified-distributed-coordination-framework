package com.udcf.demo;

import com.udcf.sync.LamportClock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * One simulated UDCF node for the standalone console demonstration.
 *
 * <p>Each node owns a {@link LamportClock} and a UDP socket on localhost, and runs two
 * threads concurrently — exactly mirroring the real system, where a node is both serving
 * requests and receiving peer messages at the same time:</p>
 *
 * <ul>
 *   <li><b>Listener thread</b> — blocks on the socket, and applies Rule 3
 *       ({@code max(local, received) + 1}) to every message that arrives.</li>
 *   <li><b>Sender thread</b> — performs local events (Rule 1) and sends timestamped
 *       messages to peers (Rule 2).</li>
 * </ul>
 *
 * <p>Real UDP sockets are used rather than in-process method calls so that the messages
 * genuinely cross the network stack and arrive with unpredictable delay. That
 * unpredictability is the whole point: it is what makes the ordering guarantee
 * non-trivial and the demonstration honest.</p>
 *
 * <p>The demo uses UDP for brevity. The production UDCF implementation carries the same
 * timestamp as an {@code X-Lamport-Time} HTTP header applied by a Spring interceptor.
 * The algorithm is identical; only the transport differs.</p>
 */
public class ClockNode implements Runnable {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** Shared so interleaved output from three threads never splits mid-line. */
    private static final Object PRINT_LOCK = new Object();

    private final int nodeId;
    private final String nodeName;
    private final String role;
    private final int port;
    private final int[] peerIds;
    private final int[] peerPorts;
    private final int rounds;

    private final LamportClock clock = new LamportClock();
    private final ClockEventLog eventLog;
    private final Random random;

    private DatagramSocket socket;
    private Thread listenerThread;
    private volatile boolean running = true;

    public ClockNode(int nodeId, String nodeName, String role, int port,
                     int[] peerIds, int[] peerPorts, int rounds, ClockEventLog eventLog) {
        this.nodeId = nodeId;
        this.nodeName = nodeName;
        this.role = role;
        this.port = port;
        this.peerIds = peerIds;
        this.peerPorts = peerPorts;
        this.rounds = rounds;
        this.eventLog = eventLog;
        this.random = new Random(nodeId * 7919L);   // seeded: varied but reproducible
    }

    /** Binds the socket and starts the listener thread. */
    public void start() throws Exception {
        socket = new DatagramSocket(port);
        socket.setSoTimeout(500);

        listenerThread = new Thread(this::listenLoop, "node" + nodeId + "-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();

        log("INIT", clock.current(), 0,
                String.format("Booting %s (role=%s) on UDP port %d, logical clock initialised to 0",
                        nodeName, role, port));
    }

    /** Sender thread body — Rule 1 and Rule 2. */
    @Override
    public void run() {
        try {
            for (int round = 1; round <= rounds && running; round++) {
                Thread.sleep(220 + random.nextInt(280));

                // Rule 1 — a purely local event, no message involved.
                if (random.nextInt(100) < 45) {
                    long before = clock.current();
                    long after = clock.tick();
                    log("LOCAL", after, 0, String.format(
                            "Local event: client request R%d%02d   (L: %d -> %d)",
                            nodeId, round, before, after));
                    Thread.sleep(80 + random.nextInt(120));
                }

                // Rule 2 — tick, then send the new value with the message.
                int index = random.nextInt(peerIds.length);
                int targetId = peerIds[index];
                int targetPort = peerPorts[index];
                String payload = "Transaction_" + round + "_from_N" + nodeId;

                long before = clock.current();
                long stamped = clock.tick();
                send(targetPort, stamped, payload);

                log("SEND", stamped, targetId, String.format(
                        "Sent %-22s to N%d  (L: %d -> %d, stamped T=%d)",
                        payload, targetId, before, stamped, stamped));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("Node " + nodeId + " sender failed: " + e.getMessage());
        }
    }

    /** Listener thread body — Rule 3. */
    private void listenLoop() {
        byte[] buffer = new byte[1024];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String raw = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                String[] parts = raw.split("\\|", 3);
                int senderId = Integer.parseInt(parts[0]);
                long receivedTime = Long.parseLong(parts[1]);
                String payload = parts[2];

                long localBefore = clock.current();
                long updated = clock.update(receivedTime);   // Rule 3

                log("RECV", updated, senderId, receivedTime, String.format(
                        "Recv %-22s from N%d (max(%d,%d)+1 = %d)",
                        payload, senderId, localBefore, receivedTime, updated));

            } catch (SocketTimeoutException ignored) {
                // Expected: lets the loop re-check the running flag during shutdown.
            } catch (Exception e) {
                if (running) {
                    System.err.println("Node " + nodeId + " listener error: " + e.getMessage());
                }
            }
        }
    }

    private void send(int targetPort, long lamportTime, String payload) throws Exception {
        String message = nodeId + "|" + lamportTime + "|" + payload;
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        DatagramPacket packet = new DatagramPacket(
                data, data.length, InetAddress.getByName("127.0.0.1"), targetPort);
        socket.send(packet);
    }

    /** Prints one line and records the event for the final ordered timeline. */
    private void log(String type, long lamportTime, int peerId, String description) {
        log(type, lamportTime, peerId, -1L, description);
    }

    /**
     * @param causedByTime for a RECV, the timestamp that arrived on the message;
     *                     -1 for every other event type
     */
    private void log(String type, long lamportTime, int peerId,
                     long causedByTime, String description) {
        LocalTime now = LocalTime.now();
        synchronized (PRINT_LOCK) {
            System.out.printf("[%s] [Node %d | Lamport: %3d] [%-5s] %s%n",
                    now.format(TIME_FMT), nodeId, lamportTime, type, description);
        }
        if (!type.equals("INIT")) {
            eventLog.record(new DemoEvent(
                    nodeId, type, lamportTime, peerId, causedByTime, description, now));
        }
    }

    public void shutdown() {
        running = false;
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    public int getNodeId()      { return nodeId; }
    public String getNodeName() { return nodeName; }
    public String getRole()     { return role; }
    public long getClockValue() { return clock.current(); }
}
