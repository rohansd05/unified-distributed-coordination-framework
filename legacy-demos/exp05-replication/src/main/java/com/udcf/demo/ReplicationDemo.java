package com.udcf.demo;

import com.udcf.replication.ConsistencyModel;
import com.udcf.replication.DataItem;
import com.udcf.replication.NodeRole;
import com.udcf.replication.ReplicationEvent;
import com.udcf.replication.ReplicationEventLog;
import com.udcf.replication.ReplicationNode;
import com.udcf.replication.ReplicationStats;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Standalone console demonstration of Experiment 5 — Data Consistency and Replication.
 *
 * <p>Boots three {@link ReplicationNode} instances on localhost TCP ports 7101 to 7103:
 * Node 1 as the PRIMARY, Nodes 2 and 3 as BACKUPs. It then runs five phases covering
 * synchronous replication, asynchronous replication and the stale-read window it creates,
 * a backup crash with a genuine replication failure, recovery with anti-entropy, and
 * last-writer-wins conflict resolution on an out-of-order update.</p>
 *
 * <p>No Spring context and no HTTP server is required, so the output can be captured
 * directly from the VS Code terminal.</p>
 *
 * <pre>
 *   javac -d target/classes src/main/java/com/udcf/sync/LamportClock.java src/main/java/com/udcf/replication/*.java src/main/java/com/udcf/demo/*.java
 *   java -cp target/classes com.udcf.demo.ReplicationDemo
 * </pre>
 */
public class ReplicationDemo {

    private static final int[] NODE_IDS   = {1, 2, 3};
    private static final int[] NODE_PORTS = {7101, 7102, 7103};
    private static final int PRIMARY = 1;
    private static final int BACKUP_A = 2;
    private static final int BACKUP_B = 3;

    public static void main(String[] args) throws Exception {
        printBanner();

        ReplicationEventLog log = new ReplicationEventLog();

        ReplicationNode primary = new ReplicationNode(PRIMARY, 7101, NodeRole.PRIMARY,
                new int[]{BACKUP_A, BACKUP_B}, NODE_IDS, NODE_PORTS, log);
        ReplicationNode backupA = new ReplicationNode(BACKUP_A, 7102, NodeRole.BACKUP,
                new int[]{}, NODE_IDS, NODE_PORTS, log);
        ReplicationNode backupB = new ReplicationNode(BACKUP_B, 7103, NodeRole.BACKUP,
                new int[]{}, NODE_IDS, NODE_PORTS, log);

        primary.start();
        backupA.start();
        backupB.start();
        System.out.println();

        // ---------------------------------------------------------- PHASE 1
        phase("PHASE 1", "SYNCHRONOUS REPLICATION  (strong consistency)",
                "The primary sends each update to both backups and blocks until both have",
                "acknowledged. Only then is the client told the write succeeded, so a read",
                "from any replica immediately afterwards must return the new value.");

        double t1 = primary.write("balance", "1000", ConsistencyModel.SYNCHRONOUS);
        double t2 = primary.write("owner", "alice", ConsistencyModel.SYNCHRONOUS);
        double t3 = primary.write("status", "active", ConsistencyModel.SYNCHRONOUS);

        TimeUnit.MILLISECONDS.sleep(200);
        System.out.println();
        System.out.printf("  Client wait times: %.1f ms, %.1f ms, %.1f ms%n", t1, t2, t3);
        readEverywhere(primary, "balance");

        // ---------------------------------------------------------- PHASE 2
        phase("PHASE 2", "ASYNCHRONOUS REPLICATION  (eventual consistency)",
                "The primary applies the write locally, confirms it immediately, and",
                "replicates in the background. The client is released far sooner, but there",
                "is now a window in which the backups still hold the OLD value.");

        double t4 = primary.write("balance", "2000", ConsistencyModel.ASYNCHRONOUS);
        System.out.println();
        System.out.printf("  Client waited only %.1f ms — compare with synchronous above.%n", t4);
        System.out.println("  Reading all three replicas IMMEDIATELY, before replication lands:");
        readEverywhere(primary, "balance");
        System.out.println("  The backups are STALE. This is the eventual-consistency window.");

        TimeUnit.MILLISECONDS.sleep(1200);
        System.out.println();
        System.out.println("  Waiting 1.2 s, then reading again:");
        readEverywhere(primary, "balance");
        System.out.println("  The replicas have now CONVERGED.");

        // ---------------------------------------------------------- PHASE 3
        phase("PHASE 3", "BACKUP FAILURE DURING REPLICATION",
                "Backup Node 3 crashes, closing its TCP port. The next synchronous write",
                "cannot reach it, so the connection is refused and the primary records a",
                "genuine replication failure while continuing to serve Node 2.");

        backupB.crash();
        TimeUnit.MILLISECONDS.sleep(300);
        primary.write("balance", "3000", ConsistencyModel.SYNCHRONOUS);
        primary.write("status", "frozen", ConsistencyModel.SYNCHRONOUS);
        TimeUnit.MILLISECONDS.sleep(200);
        System.out.println();
        System.out.println("  Node 3 is now missing two updates — the replicas have DIVERGED:");
        readEverywhere(primary, "balance");

        // ---------------------------------------------------------- PHASE 4
        phase("PHASE 4", "RECOVERY AND ANTI-ENTROPY",
                "Node 3 restarts holding stale data. The primary pushes its entire store to",
                "it. Items Node 3 already has at the same version are refused as not newer,",
                "so only the genuinely missed updates are stored and the replicas converge.");

        backupB.recover();
        TimeUnit.MILLISECONDS.sleep(300);
        primary.resync(BACKUP_B);
        TimeUnit.MILLISECONDS.sleep(300);
        System.out.println();
        readEverywhere(primary, "balance");
        System.out.println("  Node 3 has caught up without the primary tracking what it missed.");

        // ---------------------------------------------------------- PHASE 5
        phase("PHASE 5", "CONFLICT RESOLUTION  (last writer wins)",
                "A stale update is delivered to Node 2 AFTER a newer one has already arrived,",
                "which is exactly what an out-of-order network can do. The backup compares",
                "the pair (lamportTime, nodeId) and refuses to overwrite the newer value.");

        primary.write("balance", "4000", ConsistencyModel.SYNCHRONOUS);
        TimeUnit.MILLISECONDS.sleep(200);
        System.out.println();

        DataItem stale = new DataItem("balance", "STALE-999", 5, PRIMARY);
        System.out.println("  Injecting an old version (Lamport 5) that lost its way in the network:");
        primary.injectOutOfOrderUpdate(BACKUP_A, stale);
        TimeUnit.MILLISECONDS.sleep(200);
        System.out.println();
        readEverywhere(primary, "balance");
        System.out.println("  The stale write was discarded. No replica was corrupted.");

        // ---------------------------------------------------------- WRAP UP
        TimeUnit.MILLISECONDS.sleep(300);
        printReplicationHealth(primary);
        printConsistencyCheck(primary);
        printCausalLog(log);
        printSummary(log);

        primary.shutdown();
        backupA.shutdown();
        backupB.shutdown();
        TimeUnit.MILLISECONDS.sleep(200);

        System.out.println("=".repeat(100));
        System.out.println("   REPLICATION DEMONSTRATION COMPLETED");
        System.out.println("=".repeat(100));
        System.out.println();
    }

    // ------------------------------------------------------------------ output helpers

    private static void printBanner() {
        System.out.println("=".repeat(100));
        System.out.println("   UNIFIED DISTRIBUTED COORDINATION FRAMEWORK");
        System.out.println("   EXPERIMENT 5  -  DATA CONSISTENCY AND REPLICATION");
        System.out.println("   Primary-Backup replication over TCP  (standalone console demonstration)");
        System.out.println("=".repeat(100));
        System.out.println();
        System.out.println("Cluster:   Node 1 PRIMARY :7101     Node 2 BACKUP :7102     Node 3 BACKUP :7103");
        System.out.println();
        System.out.println("Synchronous   the primary waits for every backup to ACK before confirming.");
        System.out.println("              Strong consistency, paid for with client latency.");
        System.out.println("Asynchronous  the primary confirms at once and replicates in the background.");
        System.out.println("              Low latency, paid for with a stale-read window.");
        System.out.println();
        System.out.println("Transport is TCP, not the UDP of Experiment 4, because replication needs a");
        System.out.println("real acknowledgement and a refused connection to a dead backup.");
        System.out.println("Every item carries a Lamport timestamp so conflicts resolve deterministically.");
        System.out.println();
    }

    private static void phase(String label, String title, String... lines) throws Exception {
        TimeUnit.MILLISECONDS.sleep(300);
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("  " + label + "  -  " + title);
        for (String line : lines) {
            System.out.println("  " + line);
        }
        System.out.println("=".repeat(100));
        System.out.println();
    }

    /** Reads the same key from all three replicas so divergence is visible at a glance. */
    private static void readEverywhere(ReplicationNode primary, String key) {
        System.out.println();
        System.out.printf("  %-22s %-12s %s%n", "Replica", "Role", "Value of '" + key + "'");
        System.out.println("  " + "-".repeat(72));
        String[] roles = {"PRIMARY", "BACKUP", "BACKUP"};
        for (int i = 0; i < NODE_IDS.length; i++) {
            String value = primary.readFrom(NODE_IDS[i], key);
            System.out.printf("  Node %-17d %-12s %s%n", NODE_IDS[i], roles[i], value);
        }
        System.out.println();
    }

    private static void printReplicationHealth(ReplicationNode primary) {
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("  REPLICATION HEALTH   (measured, not estimated)");
        System.out.println("=".repeat(100));
        System.out.println();
        System.out.printf("  %-10s %-10s %-12s %-12s %-10s %-10s %s%n",
                "Backup", "ACKs", "Avg (ms)", "Max (ms)", "Failures", "Stale", "Last sync");
        System.out.println("  " + "-".repeat(88));
        for (ReplicationStats s : primary.stats().values()) {
            System.out.printf("  Node %-5d %-10d %-12.2f %-12.2f %-10d %-10d %s%n",
                    s.backupNodeId(), s.successCount(), s.averageLatencyMillis(),
                    s.maxLatencyMillis(), s.failureCount(), s.staleCount(),
                    s.lastSyncDescription());
        }
        System.out.println();
        System.out.println("  Every latency above is a real TCP round trip to a backup and back.");
    }

    private static void printConsistencyCheck(ReplicationNode primary) {
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("  FINAL CONSISTENCY CHECK");
        System.out.println("=".repeat(100));
        System.out.println();

        Map<String, DataItem> master = primary.fetchStore(PRIMARY);
        boolean consistent = true;

        System.out.printf("  %-12s %-28s %-28s %s%n", "Key", "Node 1 (PRIMARY)", "Node 2", "Node 3");
        System.out.println("  " + "-".repeat(94));

        Map<String, DataItem> a = primary.fetchStore(BACKUP_A);
        Map<String, DataItem> b = primary.fetchStore(BACKUP_B);

        for (String key : master.keySet()) {
            String v1 = master.get(key).shortForm();
            String v2 = a == null ? "<unreachable>"
                    : a.containsKey(key) ? a.get(key).shortForm() : "<absent>";
            String v3 = b == null ? "<unreachable>"
                    : b.containsKey(key) ? b.get(key).shortForm() : "<absent>";
            if (!v1.equals(v2) || !v1.equals(v3)) {
                consistent = false;
            }
            System.out.printf("  %-12s %-28s %-28s %s%n", key, v1, v2, v3);
        }

        System.out.println();
        if (consistent) {
            System.out.println("  PASS - all three replicas hold identical versions of every key.");
            System.out.println("         The system converged after a crash, a recovery and an");
            System.out.println("         out-of-order update, without any manual repair.");
        } else {
            System.out.println("  Replicas differ. If a phase was cut short, allow more time for");
            System.out.println("  asynchronous replication to complete before the check runs.");
        }
    }

    private static void printCausalLog(ReplicationEventLog log) {
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("  REPLICATION LOG IN TOTAL CAUSAL ORDER");
        System.out.println("  (all nodes, sorted by the pair: lamportTime, nodeId)");
        System.out.println("=".repeat(100));
        System.out.println();
        System.out.printf("  %-9s %-7s %-10s %-7s %s%n",
                "Lamport", "Node", "Category", "Peer", "Event");
        System.out.println("  " + "-".repeat(94));

        List<ReplicationEvent> ordered = log.causallyOrdered();
        for (ReplicationEvent e : ordered) {
            String peer = e.peerId() == 0 ? "-" : "N" + e.peerId();
            String desc = e.description();
            if (desc.length() > 58) {
                desc = desc.substring(0, 58);
            }
            System.out.printf("  %-9d %-7s %-10s %-7s %s%n",
                    e.lamportTime(), "N" + e.nodeId(), e.category(), peer, desc);
        }
    }

    private static void printSummary(ReplicationEventLog log) {
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("  SUMMARY");
        System.out.println("=".repeat(100));
        System.out.println();
        System.out.println("  Client writes accepted       : " + log.countByCategory("WRITE"));
        System.out.println("  Updates stored on backups    : " + log.countByCategory("APPLY"));
        System.out.println("  Acknowledgements received    : " + log.countByCategory("ACK"));
        System.out.println("  Stale updates rejected       : " + log.countByCategory("STALE"));
        System.out.println("  Replication failures         : " + log.countByCategory("FAIL"));
        System.out.println("  Anti-entropy resyncs         : " + log.countByCategory("RESYNC"));
        System.out.println("  Crash / recovery events      : "
                + (log.countByCategory("CRASH") + log.countByCategory("RECOVER")));
        System.out.println("  Total events recorded        : " + log.size());
        System.out.println();
    }
}
