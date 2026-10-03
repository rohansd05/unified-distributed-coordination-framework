package com.udcf.fault;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A client that keeps pushing system updates into the cluster, no matter what happens
 * to it.
 *
 * <p>This is the part of the experiment that makes the failure measurable. A crash with
 * nobody using the system proves nothing; a crash in the middle of a steady stream of
 * updates shows exactly how long the outage lasted and exactly what was lost.</p>
 *
 * <p>The client does not know which node is primary. It asks one, and if that node says
 * it is not the primary, it is told who is and redirects — the same way a real client
 * discovers the leader of a cluster.</p>
 */
public class UpdateClient {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** How long to keep retrying one update before declaring it failed. */
    private static final int MAX_ATTEMPTS = 14;

    private final int[] nodeIds;
    private final int[] nodePorts;
    private final AtomicInteger sequence = new AtomicInteger();

    private volatile int knownPrimary;
    private volatile boolean verbose = false;

    public UpdateClient(int[] nodeIds, int[] nodePorts, int initialPrimary) {
        this.nodeIds = nodeIds;
        this.nodePorts = nodePorts;
        this.knownPrimary = initialPrimary;
    }

    /**
     * Sends one system update, retrying through a failover if necessary.
     *
     * @return the key if the cluster confirmed it, or null if it never succeeded
     */
    public String sendUpdate(String keyPrefix, FailoverMetrics metrics) {
        int seq = sequence.incrementAndGet();
        String key = String.format("%s-%04d", keyPrefix, seq);
        String value = "v" + seq;
        if (metrics != null) {
            metrics.countAttempt();
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String response = send(knownPrimary, seq + ";" + key + ";" + value);
                String[] parts = response.split("\\|", 5);
                MessageType type = MessageType.valueOf(parts[0]);

                if (type == MessageType.ACK) {
                    if (metrics != null) {
                        metrics.countConfirmed(key);
                        metrics.markServiceRestored();
                    }
                    return key;
                }
                if (type == MessageType.NOT_PRIMARY) {
                    // The node told us who is actually in charge; follow the redirect.
                    int redirect = Integer.parseInt(parts[4].trim());
                    if (redirect > 0) {
                        knownPrimary = redirect;
                    } else {
                        rediscoverPrimary();
                    }
                }
            } catch (IOException e) {
                // The node we believed was primary is unreachable. Look for a new one.
                if (metrics != null) {
                    metrics.countRejected();
                }
                rediscoverPrimary();
            }
            sleep(120);
        }

        if (verbose) {
            log("Update " + key + " FAILED after " + MAX_ATTEMPTS + " attempts");
        }
        return null;
    }

    /** Asks every node in turn who the primary is. */
    private void rediscoverPrimary() {
        for (int id : nodeIds) {
            try {
                String response = query(id);
                String[] parts = response.split("\\|", 5);
                String[] body = parts[4].split(";");
                if (body[0].equals(NodeRole.PRIMARY.name())) {
                    knownPrimary = id;
                    return;
                }
                int believed = Integer.parseInt(body[2]);
                if (believed > 0) {
                    knownPrimary = believed;
                }
            } catch (IOException e) {
                // Try the next node.
            }
        }
    }

    private String send(int targetId, String payload) throws IOException {
        return call(targetId, MessageType.CLIENT_WRITE, payload);
    }

    private String query(int targetId) throws IOException {
        return call(targetId, MessageType.ROLE_QUERY, "");
    }

    private String call(int targetId, MessageType type, String payload) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", portOf(targetId)), 600);
            socket.setSoTimeout(1500);
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.println(type.name() + "|0|0|0|" + payload);
            String response = in.readLine();
            if (response == null) {
                throw new IOException("no reply from node " + targetId);
            }
            return response;
        }
    }

    private int portOf(int id) {
        for (int i = 0; i < nodeIds.length; i++) {
            if (nodeIds[i] == id) {
                return nodePorts[i];
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

    private void log(String message) {
        System.out.printf("[%s] [CLIENT]                      %s%n",
                LocalTime.now().format(TIME_FMT), message);
    }

    public void setVerbose(boolean v)   { this.verbose = v; }
    public void setKnownPrimary(int id) { this.knownPrimary = id; }
    public int knownPrimary()           { return knownPrimary; }
    public void resetSequence()         { sequence.set(0); }
}
