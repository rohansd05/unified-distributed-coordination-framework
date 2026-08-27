package com.udcf.demo;

import com.udcf.election.ElectionEvent;
import com.udcf.election.ElectionEventLog;
import com.udcf.election.ElectionNode;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Standalone console demonstration of Experiment 4 — Leader Election.
 *
 * <p>Boots five {@link ElectionNode} instances on localhost UDP ports 7001 to 7005 and
 * drives them through five phases: an initial Bully election, a leader crash with
 * automatic detection and re-election, a Ring election, and the recovery of the crashed
 * highest-ID node reclaiming leadership.</p>
 *
 * <p>Five nodes are used rather than the three the deployed framework runs, because with
 * only three the Bully message cascade (ELECTION to several higher nodes, OK from each,
 * then COORDINATOR) is barely visible. The node count is a constant here and comes from
 * configuration in the real system, so the algorithm itself is unchanged.</p>
 *
 * <p>No Spring context and no HTTP server is required, so the output can be captured
 * directly from the VS Code terminal.</p>
 *
 * <pre>
 *   javac -d target/classes src/main/java/com/udcf/sync/LamportClock.java src/main/java/com/udcf/election/*.java src/main/java/com/udcf/demo/*.java
 *   java -cp target/classes com.udcf.demo.ElectionDemo
 * </pre>
 */
public class ElectionDemo {

    private static final int[] NODE_IDS   = {1, 2, 3, 4, 5};
    private static final int[] NODE_PORTS = {7001, 7002, 7003, 7004, 7005};

    public static void main(String[] args) throws Exception {
        printBanner();

        ElectionEventLog eventLog = new ElectionEventLog();
        ElectionNode[] nodes = new ElectionNode[NODE_IDS.length];

        for (int i = 0; i < NODE_IDS.length; i++) {
            nodes[i] = new ElectionNode(NODE_IDS[i], NODE_PORTS[i],
                    NODE_IDS, NODE_PORTS, eventLog);
            nodes[i].start();
        }

        phase("PHASE 1", "INITIAL BULLY ELECTION",
                "No coordinator exists yet. Node 2 starts an election. It challenges every",
                "higher node, so Nodes 3, 4 and 5 all reply OK and Node 5 - the highest -",
                "should win and announce itself.");
        new Thread(nodes[1]::startBullyElection).start();
        TimeUnit.MILLISECONDS.sleep(6000);
        printLeaderState(nodes);

        phase("PHASE 2", "LEADER FAILURE AND AUTOMATIC RE-ELECTION",
                "Node 5, the current coordinator, crashes. It stops answering heartbeats.",
                "The surviving nodes should detect the silence, start a Bully election on",
                "their own, and elect Node 4 as the new coordinator without any human help.");
        nodes[4].crash();
        TimeUnit.MILLISECONDS.sleep(9000);
        printLeaderState(nodes);

        phase("PHASE 3", "RING ELECTION",
                "The same cluster now runs the Ring algorithm instead. Node 1 starts a token",
                "that travels around the ring collecting node IDs. Node 5 is still down, so",
                "the liveness probe should skip it rather than losing the token.");
        new Thread(nodes[0]::startRingElection).start();
        TimeUnit.MILLISECONDS.sleep(7000);
        printLeaderState(nodes);

        phase("PHASE 4", "RECOVERY OF THE HIGHEST NODE",
                "Node 5 restarts. Because it holds the highest ID it immediately calls an",
                "election and bullies its way back to coordinator - this is the behaviour",
                "the algorithm is named after.");
        nodes[4].recover();
        TimeUnit.MILLISECONDS.sleep(1000);
        new Thread(nodes[4]::startBullyElection).start();
        TimeUnit.MILLISECONDS.sleep(6000);
        printLeaderState(nodes);

        for (ElectionNode node : nodes) {
            node.shutdown();
        }
        TimeUnit.MILLISECONDS.sleep(300);

        printCausalTimeline(eventLog);
        printSummary(nodes, eventLog);
    }

    private static void printBanner() {
        System.out.println("=".repeat(100));
        System.out.println("   UNIFIED DISTRIBUTED COORDINATION FRAMEWORK");
        System.out.println("   EXPERIMENT 4  -  LEADER ELECTION");
        System.out.println("   Bully and Ring Algorithms  (standalone console demonstration)");
        System.out.println("=".repeat(100));
        System.out.println();
        System.out.println("Bully algorithm:");
        System.out.println("   a node challenges every HIGHER id;  silence means it wins;");
        System.out.println("   any OK reply means it stands down and waits to be told the result.");
        System.out.println();
        System.out.println("Ring algorithm:");
        System.out.println("   an election token circulates collecting node ids;");
        System.out.println("   when it returns to its originator the HIGHEST id in the token wins.");
        System.out.println();
        System.out.println("Failure detection:");
        System.out.println("   each node pings the coordinator; missing replies trigger an election.");
        System.out.println();
        System.out.println("Cluster: Node 1..5 on UDP ports 7001..7005   (highest id = strongest)");
        System.out.println("Every message carries a Lamport timestamp, so the merged election log");
        System.out.println("below can be replayed in causal order despite out-of-order UDP delivery.");
        System.out.println();
    }

    private static void phase(String label, String title, String... lines) throws Exception {
        TimeUnit.MILLISECONDS.sleep(400);
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("  " + label + "  -  " + title);
        for (String line : lines) {
            System.out.println("  " + line);
        }
        System.out.println("=".repeat(100));
        System.out.println();
    }

    private static void printLeaderState(ElectionNode[] nodes) {
        System.out.println();
        System.out.printf("  %-10s %-10s %-16s %-10s%n",
                "Node", "State", "Believes leader", "Lamport");
        System.out.println("  " + "-".repeat(52));
        for (ElectionNode node : nodes) {
            String leader = node.getCoordinatorId() == -1
                    ? "unknown" : "Node " + node.getCoordinatorId();
            System.out.printf("  Node %-5d %-10s %-16s %-10d%n",
                    node.getNodeId(),
                    node.isAlive() ? "ALIVE" : "CRASHED",
                    node.isAlive() ? leader : "-",
                    node.getClockValue());
        }
        System.out.println();
    }

    private static void printCausalTimeline(ElectionEventLog log) {
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("  MERGED ELECTION LOG IN TOTAL CAUSAL ORDER");
        System.out.println("  (all nodes, sorted by the pair: lamportTime, nodeId)");
        System.out.println("=".repeat(100));
        System.out.println();
        System.out.printf("  %-9s %-7s %-10s %-7s %s%n",
                "Lamport", "Node", "Category", "Peer", "Event");
        System.out.println("  " + "-".repeat(94));

        List<ElectionEvent> ordered = log.causallyOrdered();
        for (ElectionEvent e : ordered) {
            String peer = e.peerId() == 0 ? "-" : "N" + e.peerId();
            String desc = e.description();
            if (desc.length() > 60) {
                desc = desc.substring(0, 60);
            }
            System.out.printf("  %-9d %-7s %-10s %-7s %s%n",
                    e.lamportTime(), "N" + e.nodeId(), e.category(), peer, desc);
        }
        System.out.println();
        System.out.println("  Note: this ordering is produced by the Lamport clocks from Experiment 3.");
        System.out.println("  Without them the log could only be sorted by wall clock, which would");
        System.out.println("  place some receives before the sends that caused them.");
    }

    private static void printSummary(ElectionNode[] nodes, ElectionEventLog log) {
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("  SUMMARY");
        System.out.println("=".repeat(100));
        System.out.println();
        System.out.println("  Elections started            : " + log.countByCategory("ELECTION"));
        System.out.println("  Ring elections started       : " + log.countByCategory("RING"));
        System.out.println("  Coordinators elected         : " + log.countByCategory("ELECTED"));
        System.out.println("  Leader failures detected     : " + log.countByCategory("DETECT"));
        System.out.println("  Dead successors skipped      : " + log.countByCategory("SKIP"));
        System.out.println("  Crash / recovery events      : "
                + (log.countByCategory("CRASH") + log.countByCategory("RECOVER")));
        System.out.println("  Messages sent                : " + log.countByCategory("SEND"));
        System.out.println("  Total events recorded        : " + log.size());
        System.out.println();

        int expected = -1;
        boolean agreed = true;
        for (ElectionNode node : nodes) {
            if (!node.isAlive()) {
                continue;
            }
            if (expected == -1) {
                expected = node.getCoordinatorId();
            } else if (node.getCoordinatorId() != expected) {
                agreed = false;
            }
        }

        System.out.println("  CONSENSUS CHECK");
        System.out.println("  ---------------");
        if (agreed && expected != -1) {
            System.out.println("  PASS - every live node agrees that Node " + expected
                    + " is the coordinator.");
            System.out.println("         Exactly one leader exists, which is the safety property");
            System.out.println("         both algorithms are required to guarantee.");
        } else {
            System.out.println("  Live nodes do not yet agree on a single coordinator.");
            System.out.println("  This can happen if the run was cut short while an election");
            System.out.println("  was still converging - allow more time between phases.");
        }

        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("   LEADER ELECTION DEMONSTRATION COMPLETED");
        System.out.println("=".repeat(100));
        System.out.println();
    }
}
