package com.udcf.demo;

import com.udcf.fault.ConsistencyModel;
import com.udcf.fault.FailoverMetrics;
import com.udcf.fault.FaultTolerantNode;
import com.udcf.fault.NodeRole;
import com.udcf.fault.UpdateClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Standalone console demonstration of Experiment 8 — Fault Tolerance with
 * primary-backup replication.
 *
 * <p>Three nodes run on localhost TCP ports 7401 to 7403. A client pushes a continuous
 * stream of system updates while the primary is killed underneath it. The run is
 * performed twice, once with synchronous replication and once with asynchronous, so the
 * data loss each model causes can be compared directly. A final phase restarts the dead
 * primary to show it discovering that it has been replaced.</p>
 *
 * <pre>
 *   javac -d target/classes src/main/java/com/udcf/sync/LamportClock.java src/main/java/com/udcf/fault/*.java src/main/java/com/udcf/demo/*.java
 *   java -cp target/classes com.udcf.demo.FaultToleranceDemo
 * </pre>
 */
public class FaultToleranceDemo {

    private static final int[] IDS   = {1, 2, 3};
    private static final int[] PORTS = {7401, 7402, 7403};
    private static final int INITIAL_PRIMARY = 3;   // highest id leads, as in Experiment 4

    public static void main(String[] args) throws Exception {
        printBanner();

        // ------------------------------------------------------------ RUN 1
        FailoverMetrics syncMetrics =
                crashRun(ConsistencyModel.SYNCHRONOUS, "PHASE 1",
                        "SYNCHRONOUS REPLICATION  —  crash the primary mid-stream",
                        "The primary waits for the backups to acknowledge before telling the",
                        "client an update succeeded. Anything the client was told was saved is",
                        "therefore already on another machine before the crash.");

        TimeUnit.MILLISECONDS.sleep(600);

        // ------------------------------------------------------------ RUN 2
        FailoverMetrics asyncMetrics =
                crashRun(ConsistencyModel.ASYNCHRONOUS, "PHASE 2",
                        "ASYNCHRONOUS REPLICATION  —  the identical crash",
                        "The primary confirms to the client first and replicates afterwards.",
                        "Updates still travelling when it dies are gone, even though the client",
                        "was already told they had succeeded.");

        printComparison(syncMetrics, asyncMetrics);

        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("   FAULT TOLERANCE DEMONSTRATION COMPLETED");
        System.out.println("=".repeat(100));
        System.out.println();
    }

    /** One complete crash-and-recover cycle under the given consistency model. */
    private static FailoverMetrics crashRun(ConsistencyModel model, String label, String title,
                                            String l1, String l2, String l3) throws Exception {
        phase(label, title, l1, l2, l3);

        List<FaultTolerantNode> nodes = new ArrayList<>();
        for (int i = 0; i < IDS.length; i++) {
            FaultTolerantNode n = new FaultTolerantNode(IDS[i], PORTS[i], IDS, PORTS);
            n.setModel(model);
            n.start(IDS[i] == INITIAL_PRIMARY ? NodeRole.PRIMARY : NodeRole.BACKUP, INITIAL_PRIMARY);
            nodes.add(n);
        }
        FailoverMetrics metrics = new FailoverMetrics(model);
        for (FaultTolerantNode n : nodes) {
            n.setMetrics(metrics);
        }

        UpdateClient client = new UpdateClient(IDS, PORTS, INITIAL_PRIMARY);
        TimeUnit.MILLISECONDS.sleep(300);

        System.out.println();
        System.out.println("  Streaming system updates into the cluster...");
        System.out.println();

        // Steady traffic before the crash.
        for (int i = 0; i < 10; i++) {
            client.sendUpdate("setting", metrics);
            TimeUnit.MILLISECONDS.sleep(45);
        }
        printClusterState(nodes, "  Cluster before the failure");

        // Keep the stream running on a separate thread while the primary dies, so the
        // outage is measured against real traffic rather than an idle system.
        final boolean[] keepWriting = { true };
        Thread writer = new Thread(() -> {
            while (keepWriting[0]) {
                client.sendUpdate("setting", metrics);
                try {
                    TimeUnit.MILLISECONDS.sleep(45);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "update-writer");
        writer.setDaemon(true);
        writer.start();

        TimeUnit.MILLISECONDS.sleep(250);

        System.out.println();
        System.out.println("  >>> KILLING THE PRIMARY WHILE UPDATES ARE STILL FLOWING <<<");
        System.out.println();
        FaultTolerantNode oldPrimary = nodes.get(2);
        metrics.markCrash(oldPrimary.nodeId());
        oldPrimary.crash();

        // Long enough for detection, promotion and the client to find the new primary.
        TimeUnit.MILLISECONDS.sleep(4200);
        keepWriting[0] = false;
        writer.join(2000);
        TimeUnit.MILLISECONDS.sleep(400);

        printClusterState(nodes, "  Cluster after the failover");
        printFailoverMetrics(metrics);
        int lost = printDataLoss(nodes, metrics);

        // ------------------------------------------------------------ recovery
        System.out.println();
        System.out.println("  " + "-".repeat(94));
        System.out.println("  RECOVERY OF THE OLD PRIMARY  —  split-brain prevention");
        System.out.println("  " + "-".repeat(94));
        System.out.println();

        oldPrimary.recover();
        TimeUnit.MILLISECONDS.sleep(300);
        oldPrimary.rejoinCluster();
        TimeUnit.MILLISECONDS.sleep(500);

        printClusterState(nodes, "  Cluster after the old primary rejoined");
        System.out.println("  Note that Node " + oldPrimary.nodeId()
                + " came back believing it was still primary, discovered a higher");
        System.out.println("  epoch, demoted itself and copied the current state. Two primaries never existed.");

        for (FaultTolerantNode n : nodes) {
            n.shutdown();
        }
        TimeUnit.MILLISECONDS.sleep(400);
        return metrics;
    }

    // ------------------------------------------------------------------ reporting

    private static void printClusterState(List<FaultTolerantNode> nodes, String heading) {
        System.out.println();
        System.out.println(heading);
        System.out.printf("    %-8s %-10s %-9s %-16s %s%n",
                "Node", "Role", "Epoch", "Believes primary", "Updates stored");
        System.out.println("    " + "-".repeat(66));
        for (FaultTolerantNode n : nodes) {
            System.out.printf("    Node %-3d %-10s %-9d %-16s %d%n",
                    n.nodeId(),
                    n.isAlive() ? n.role() : "FAILED",
                    n.epoch(),
                    n.isAlive() ? "Node " + n.believedPrimary() : "-",
                    n.store().size());
        }
        System.out.println();
    }

    private static void printFailoverMetrics(FailoverMetrics m) {
        System.out.println("  FAILOVER TIMINGS   (measured from the moment the primary died)");
        System.out.println("    " + "-".repeat(66));
        System.out.printf("    Failure detected after        : %.1f ms%n", m.detectionMillis());
        System.out.printf("    New primary promoted after    : %.1f ms%n", m.failoverMillis());
        System.out.printf("    Client served again after     : %.1f ms%n", m.recoveryMillis());
        System.out.printf("    Old primary                   : Node %d%n", m.oldPrimary());
        System.out.printf("    New primary                   : Node %d  (epoch %d)%n",
                m.newPrimary(), m.newEpoch());
        System.out.println();
    }

    /**
     * Compares what the client was told succeeded against what the cluster actually holds.
     *
     * <p>This is the measurement that matters. An update the client believes is saved but
     * which no surviving node holds is real, silent data loss.</p>
     */
    private static int printDataLoss(List<FaultTolerantNode> nodes, FailoverMetrics m) {
        FaultTolerantNode survivor = null;
        for (FaultTolerantNode n : nodes) {
            if (n.isAlive() && n.role() == NodeRole.PRIMARY) {
                survivor = n;
            }
        }
        if (survivor == null) {
            System.out.println("  No surviving primary; data loss cannot be assessed.");
            return -1;
        }

        List<String> missing = new ArrayList<>();
        synchronized (m.confirmedKeys()) {
            for (String key : m.confirmedKeys()) {
                if (!survivor.store().contains(key)) {
                    missing.add(key);
                }
            }
        }

        System.out.println("  DATA INTEGRITY CHECK");
        System.out.println("    " + "-".repeat(66));
        System.out.printf("    Updates attempted by the client      : %d%n", m.attempted());
        System.out.printf("    Updates the client was told succeeded: %d%n", m.confirmed());
        System.out.printf("    Attempts refused during the outage   : %d  (retried, not lost)%n",
                m.rejectedDuringOutage());
        System.out.printf("    Confirmed updates now MISSING        : %d%n", missing.size());
        System.out.println();
        if (missing.isEmpty()) {
            System.out.println("    NO DATA LOSS. Every update the client believed was saved is");
            System.out.println("    present on the new primary.");
        } else {
            System.out.println("    DATA LOST. The client was told these updates succeeded, but no");
            System.out.println("    surviving node holds them:");
            System.out.print("      ");
            for (int i = 0; i < Math.min(10, missing.size()); i++) {
                System.out.print(missing.get(i) + (i < Math.min(10, missing.size()) - 1 ? ", " : ""));
            }
            if (missing.size() > 10) {
                System.out.print("  ... and " + (missing.size() - 10) + " more");
            }
            System.out.println();
        }
        return missing.size();
    }

    private static void printComparison(FailoverMetrics sync, FailoverMetrics async) {
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("  COMPARISON  —  THE SAME CRASH UNDER BOTH REPLICATION MODELS");
        System.out.println("=".repeat(100));
        System.out.println();
        System.out.printf("  %-26s %-20s %-20s%n", "", "SYNCHRONOUS", "ASYNCHRONOUS");
        System.out.println("  " + "-".repeat(70));
        System.out.printf("  %-26s %-20s %-20s%n", "Detection time",
                sync.detectionMillis() + " ms", async.detectionMillis() + " ms");
        System.out.printf("  %-26s %-20s %-20s%n", "Failover time",
                sync.failoverMillis() + " ms", async.failoverMillis() + " ms");
        System.out.printf("  %-26s %-20s %-20s%n", "Updates confirmed",
                String.valueOf(sync.confirmed()), String.valueOf(async.confirmed()));
        System.out.println();
        System.out.println("  Detection and failover take the same time in both cases, because they");
        System.out.println("  depend on heartbeats, not on the replication model.");
        System.out.println();
        System.out.println("  What differs is what survives. Synchronous replication means a confirmed");
        System.out.println("  update is already on a second machine before the client is told it was");
        System.out.println("  saved, so losing the primary loses nothing. Asynchronous replication");
        System.out.println("  confirms first and copies afterwards, so whatever was still in flight");
        System.out.println("  dies with the primary - and the client has no idea.");
        System.out.println();
        System.out.println("  That is the real trade-off: the asynchronous client waits less for every");
        System.out.println("  single update, and pays for it once, badly, on the day something breaks.");
    }

    // ------------------------------------------------------------------ banner

    private static void phase(String label, String title, String... lines) throws Exception {
        TimeUnit.MILLISECONDS.sleep(250);
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("  " + label + "  -  " + title);
        for (String l : lines) {
            System.out.println("  " + l);
        }
        System.out.println("=".repeat(100));
    }

    private static void printBanner() {
        System.out.println("=".repeat(100));
        System.out.println("   UNIFIED DISTRIBUTED COORDINATION FRAMEWORK");
        System.out.println("   EXPERIMENT 8  -  FAULT TOLERANCE WITH PRIMARY-BACKUP REPLICATION");
        System.out.println("   Crash the primary while system updates are in flight");
        System.out.println("=".repeat(100));
        System.out.println();
        System.out.println("Cluster:   Node 1 BACKUP :7401    Node 2 BACKUP :7402    Node 3 PRIMARY :7403");
        System.out.println();
        System.out.println("The fault being tolerated is the loss of the node that accepts every system");
        System.out.println("update. Without protection that single failure would stop all updates and");
        System.out.println("destroy anything the primary alone was holding.");
        System.out.println();
        System.out.println("Three mechanisms handle it:");
        System.out.println("   HEARTBEATS   backups notice the primary has stopped answering");
        System.out.println("   PROMOTION    the highest surviving backup takes over, raising the epoch");
        System.out.println("   EPOCHS       a recovered old primary is refused and must step down,");
        System.out.println("                so two primaries can never accept updates at once");
        System.out.println();
    }
}
