package com.udcf.demo;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Standalone console demonstration of Experiment 3 — Clock Synchronization.
 *
 * <p>Boots three {@link ClockNode} instances on localhost UDP ports 6001, 6002 and 6003.
 * Each node runs a sender and a listener thread concurrently and exchanges timestamped
 * messages, applying Lamport's three rules. No Spring context and no HTTP server is
 * required, so the output can be captured directly from the VS Code terminal.</p>
 *
 * <p>The class it depends on for the algorithm — {@code com.udcf.sync.LamportClock} — is
 * the same production class the real UDCF nodes use. Only the transport differs: this
 * demo uses UDP datagrams, while the deployed system carries the timestamp as an
 * {@code X-Lamport-Time} HTTP header applied by a Spring interceptor.</p>
 *
 * <p>Run from VS Code with the Run button above {@code main}, or:</p>
 * <pre>
 *   javac -d target/demo-classes src/main/java/com/udcf/sync/LamportClock.java src/main/java/com/udcf/demo/*.java
 *   java -cp target/demo-classes com.udcf.demo.ClockSyncDemo
 * </pre>
 */
public class ClockSyncDemo {

    private static final int ROUNDS_PER_NODE = 5;

    public static void main(String[] args) throws Exception {
        printBanner();

        ClockEventLog eventLog = new ClockEventLog();

        ClockNode node1 = new ClockNode(1, "Node-1", "LEADER", 6001,
                new int[]{2, 3}, new int[]{6002, 6003}, ROUNDS_PER_NODE, eventLog);
        ClockNode node2 = new ClockNode(2, "Node-2", "WORKER", 6002,
                new int[]{1, 3}, new int[]{6001, 6003}, ROUNDS_PER_NODE, eventLog);
        ClockNode node3 = new ClockNode(3, "Node-3", "BACKUP", 6003,
                new int[]{1, 2}, new int[]{6001, 6002}, ROUNDS_PER_NODE, eventLog);

        List<ClockNode> nodes = List.of(node1, node2, node3);

        for (ClockNode node : nodes) {
            node.start();
        }

        System.out.println();
        System.out.println("All nodes online. Logical clocks start at 0 and are coordinated");
        System.out.println("only by the messages the nodes exchange.");
        System.out.println();
        System.out.println("-".repeat(100));
        System.out.println();

        Thread t1 = new Thread(node1, "node1-sender");
        Thread t2 = new Thread(node2, "node2-sender");
        Thread t3 = new Thread(node3, "node3-sender");

        t1.start(); t2.start(); t3.start();
        t1.join();  t2.join();  t3.join();

        // Let in-flight datagrams land before shutting the listeners down.
        TimeUnit.MILLISECONDS.sleep(900);
        for (ClockNode node : nodes) {
            node.shutdown();
        }
        TimeUnit.MILLISECONDS.sleep(200);

        printFinalState(nodes, eventLog);
        printCausalTimeline(eventLog);
        printVerification(eventLog);
    }

    private static void printBanner() {
        System.out.println("=".repeat(100));
        System.out.println("   UNIFIED DISTRIBUTED COORDINATION FRAMEWORK");
        System.out.println("   EXPERIMENT 3  -  CLOCK SYNCHRONIZATION");
        System.out.println("   Lamport Logical Clock  (standalone console demonstration)");
        System.out.println("=".repeat(100));
        System.out.println();
        System.out.println("Algorithm rules in use:");
        System.out.println("   Rule 1  local event    ->  clock = clock + 1");
        System.out.println("   Rule 2  send message   ->  clock = clock + 1, value travels with the message");
        System.out.println("   Rule 3  recv message   ->  clock = max(local, received) + 1");
        System.out.println();
        System.out.println("Cluster:  Node-1 (LEADER, :6001)   Node-2 (WORKER, :6002)   Node-3 (BACKUP, :6003)");
        System.out.println("Transport: UDP over 127.0.0.1 — real sockets, so message delay is genuinely variable.");
        System.out.println();
    }

    private static void printFinalState(List<ClockNode> nodes, ClockEventLog log) {
        System.out.println();
        System.out.println("-".repeat(100));
        System.out.println();
        System.out.println("FINAL LOGICAL CLOCK STATE");
        System.out.println();
        System.out.printf("  %-10s %-10s %-16s %-10s %-10s %-10s%n",
                "Node", "Role", "Final Lamport", "LOCAL", "SEND", "RECV");
        System.out.println("  " + "-".repeat(70));
        for (ClockNode node : nodes) {
            System.out.printf("  %-10s %-10s %-16d %-10d %-10d %-10d%n",
                    node.getNodeName(), node.getRole(), node.getClockValue(),
                    log.countForNode(node.getNodeId(), "LOCAL"),
                    log.countForNode(node.getNodeId(), "SEND"),
                    log.countForNode(node.getNodeId(), "RECV"));
        }
        System.out.println();
        System.out.println("  Note: the three counters do NOT end equal, and that is correct.");
        System.out.println("  Lamport clocks are not meant to converge to one value — they are meant");
        System.out.println("  to preserve ordering. Convergence would be physical synchronization,");
        System.out.println("  which answers a different question entirely.");
    }

    private static void printCausalTimeline(ClockEventLog log) {
        System.out.println();
        System.out.println("-".repeat(100));
        System.out.println();
        System.out.println("TOTAL CAUSAL ORDER   (all events sorted by the pair: lamportTime, nodeId)");
        System.out.println();
        System.out.printf("  %-9s %-8s %-8s %-7s %s%n", "Lamport", "Node", "Type", "Peer", "Event");
        System.out.println("  " + "-".repeat(100));

        for (DemoEvent e : log.causallyOrdered()) {
            String peer = e.peerId() == 0 ? "-" : "N" + e.peerId();
            String shortDesc = e.description().length() > 62
                    ? e.description().substring(0, 62) : e.description();
            System.out.printf("  %-9d %-8s %-8s %-7s %s%n",
                    e.lamportTime(), "N" + e.nodeId(), e.type(), peer, shortDesc.trim());
        }
        System.out.println();
        System.out.println("  Equal Lamport values on different nodes mean those events are CONCURRENT —");
        System.out.println("  neither caused the other. The nodeId tie-break gives them a deterministic");
        System.out.println("  position so the ordering is identical on every node and on every run.");
    }

    /**
     * Proves the causal invariant held, rather than merely asserting that it did.
     *
     * <p>For every receive event we recorded the timestamp that actually arrived on the
     * message. Lamport's guarantee is that the resulting local timestamp must be
     * strictly greater than that value. Any receive where this fails would mean the
     * update rule was applied incorrectly, or that a concurrent update was lost.</p>
     */
    private static void printVerification(ClockEventLog log) {
        System.out.println();
        System.out.println("-".repeat(100));
        System.out.println();
        System.out.println("CAUSAL INVARIANT VERIFICATION");
        System.out.println();
        System.out.println("  Checking:  for every RECV,  localTimestamp  >  timestampCarriedOnMessage");
        System.out.println();

        int checked = 0;
        int violations = 0;

        for (DemoEvent e : log.causallyOrdered()) {
            if (!e.type().equals("RECV")) {
                continue;
            }
            checked++;
            if (e.lamportTime() <= e.causedByTime()) {
                violations++;
                System.out.printf("  VIOLATION on Node %d: local=%d  received=%d%n",
                        e.nodeId(), e.lamportTime(), e.causedByTime());
            }
        }

        System.out.println("  Total events recorded          : " + log.size());
        System.out.println("  Local events (Rule 1)          : " + log.countByType("LOCAL"));
        System.out.println("  Messages sent (Rule 2)         : " + log.countByType("SEND"));
        System.out.println("  Messages received (Rule 3)     : " + log.countByType("RECV"));
        System.out.println("  Receive events checked         : " + checked);
        System.out.println("  Ordering violations detected   : " + violations);
        System.out.println();

        if (violations == 0) {
            System.out.println("  PASS - every receive event carries a strictly greater Lamport timestamp");
            System.out.println("         than the send event that caused it. Causality was preserved across");
            System.out.println("         all three nodes despite genuinely variable network delay.");
        } else {
            System.out.println("  FAIL - causality violated. Check the update rule in LamportClock.");
        }

        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("   CLOCK SYNCHRONIZATION DEMONSTRATION COMPLETED");
        System.out.println("=".repeat(100));
        System.out.println();
    }
}
