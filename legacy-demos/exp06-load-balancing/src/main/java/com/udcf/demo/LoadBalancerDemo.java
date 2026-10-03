package com.udcf.demo;

import com.udcf.loadbalancer.LoadBalancer;
import com.udcf.loadbalancer.PhaseReport;
import com.udcf.loadbalancer.Strategy;
import com.udcf.loadbalancer.WorkerInfo;
import com.udcf.loadbalancer.WorkerNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Standalone console demonstration of Experiment 6 — Load Balancing.
 *
 * <p>Boots three worker nodes of deliberately unequal capacity on localhost TCP ports
 * 7201 to 7203, then sends the same batch of concurrent requests through four different
 * balancing algorithms and compares what happened.</p>
 *
 * <pre>
 *   javac -d target/classes src/main/java/com/udcf/sync/LamportClock.java src/main/java/com/udcf/loadbalancer/*.java src/main/java/com/udcf/demo/*.java
 *   java -cp target/classes com.udcf.demo.LoadBalancerDemo
 * </pre>
 */
public class LoadBalancerDemo {

    private static final int REQUESTS   = 60;
    private static final int WORK_UNITS = 900;
    private static final int CONCURRENT_CLIENTS = 12;

    public static void main(String[] args) throws Exception {
        printBanner();

        // Deliberately unequal: a fast node, a medium node and a slow node.
        // On identical workers every algorithm behaves the same and proves nothing.
        WorkerNode fast   = new WorkerNode(1, 7201, "FAST",   4, 1);
        WorkerNode medium = new WorkerNode(2, 7202, "MEDIUM", 2, 2);
        WorkerNode slow   = new WorkerNode(3, 7203, "SLOW",   1, 4);
        List<WorkerNode> nodes = List.of(fast, medium, slow);
        for (WorkerNode n : nodes) { n.start(); }

        List<WorkerInfo> infos = List.of(
                new WorkerInfo(1, 7201, "FAST",   4),
                new WorkerInfo(2, 7202, "MEDIUM", 2),
                new WorkerInfo(3, 7203, "SLOW",   1));
        LoadBalancer lb = new LoadBalancer(new ArrayList<>(infos), CONCURRENT_CLIENTS);

        printCluster(nodes);
        TimeUnit.MILLISECONDS.sleep(300);

        Map<Strategy, PhaseReport> reports = new LinkedHashMap<>();

        reports.put(Strategy.ROUND_ROBIN, phase(lb, nodes, Strategy.ROUND_ROBIN,
                "Take each worker in turn. Uses no information about them at all.",
                "Expect a perfectly even split - and the slow node holding everyone up."));

        reports.put(Strategy.WEIGHTED_ROUND_ROBIN, phase(lb, nodes, Strategy.WEIGHTED_ROUND_ROBIN,
                "Round robin biased by a configured capacity weight of 4 : 2 : 1.",
                "Expect the fast node to take most of the work, using knowledge set in advance."));

        reports.put(Strategy.LEAST_CONNECTIONS, phase(lb, nodes, Strategy.LEAST_CONNECTIONS,
                "Send to whichever worker has the fewest requests still outstanding.",
                "Expect a split close to the weighted one - but discovered live, not configured."));

        reports.put(Strategy.LEAST_RESPONSE_TIME, phase(lb, nodes, Strategy.LEAST_RESPONSE_TIME,
                "Send to the worker with the lowest measured time-to-finish.",
                "Expect the best tail latency, because it reacts to how slow a node actually is."));

        printComparison(reports);

        // ------------------------------------------------------------ failure phase
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("  PHASE 5  -  WORKER FAILURE DURING A RUN");
        System.out.println("  The SLOW worker is taken offline. Its TCP port closes, so the balancer's");
        System.out.println("  connection is refused. It should mark that worker unhealthy, reroute the");
        System.out.println("  request to a survivor, and finish the batch with zero failed requests.");
        System.out.println("=".repeat(100));
        System.out.println();

        lb.restoreAllHealthy();
        for (WorkerNode n : nodes) { n.resetCounters(); }
        slow.crash();
        System.out.println("  Node 3 (SLOW) has been taken offline.");
        TimeUnit.MILLISECONDS.sleep(300);

        PhaseReport failover = lb.runBatch(Strategy.LEAST_CONNECTIONS, REQUESTS, WORK_UNITS);
        printDistribution(lb, failover, nodes);
        System.out.println();
        System.out.printf("  Requests rerouted after a refused connection : %d%n", failover.reroutes());
        System.out.printf("  Requests that failed outright                : %d%n", failover.failures());
        System.out.println();
        if (failover.failures() == 0) {
            System.out.println("  PASS - every request was still served. A worker failure became a");
            System.out.println("         slightly slower request, not a lost one.");
        }

        slow.recover();
        lb.restoreAllHealthy();
        System.out.println();
        System.out.println("  Node 3 is back online and eligible for dispatch again.");

        for (WorkerNode n : nodes) { n.shutdown(); }
        lb.shutdown();
        TimeUnit.MILLISECONDS.sleep(200);

        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("   LOAD BALANCING DEMONSTRATION COMPLETED");
        System.out.println("=".repeat(100));
        System.out.println();
    }

    // ------------------------------------------------------------------ phases

    private static PhaseReport phase(LoadBalancer lb, List<WorkerNode> nodes,
                                     Strategy strategy, String what, String expect)
            throws Exception {
        TimeUnit.MILLISECONDS.sleep(300);
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("  " + strategy.name().replace('_', ' '));
        System.out.println("  " + what);
        System.out.println("  " + expect);
        System.out.println("=".repeat(100));

        for (WorkerNode n : nodes) { n.resetCounters(); }
        PhaseReport report = lb.runBatch(strategy, REQUESTS, WORK_UNITS);
        printDistribution(lb, report, nodes);
        return report;
    }

    private static void printDistribution(LoadBalancer lb, PhaseReport report,
                                          List<WorkerNode> nodes) {
        System.out.println();
        System.out.printf("  %-8s %-10s %-10s %-12s %-14s %-14s %s%n",
                "Node", "Profile", "Threads", "Requests", "Share", "Avg (ms)", "Peak queue");
        System.out.println("  " + "-".repeat(90));

        Map<Integer, Integer> counts = report.requestsPerNode();
        for (WorkerNode n : nodes) {
            int c = counts.getOrDefault(n.nodeId(), 0);
            double share = report.total() == 0 ? 0 : (100.0 * c / report.total());
            double avg = 0;
            for (WorkerInfo w : lb.workers()) {
                if (w.nodeId() == n.nodeId()) { avg = w.averageLatencyMillis(); }
            }
            System.out.printf("  %-8d %-10s %-10d %-12d %-14s %-14s %d%n",
                    n.nodeId(), n.label(), n.poolSize(), c,
                    String.format("%.1f %%", share),
                    String.format("%.1f", avg), n.peakQueue());
        }
        System.out.println();
        System.out.printf("  Total time for all %d requests (makespan) : %.1f ms%n",
                report.total(), report.makespanMillis());
        System.out.printf("  Average request latency                  : %.1f ms%n", report.averageLatency());
        System.out.printf("  95th percentile latency                  : %.1f ms%n", report.p95Latency());
        System.out.printf("  Slowest single request                   : %.1f ms%n", report.maxLatency());
        System.out.printf("  Request-count spread (busiest - idlest)   : %d%n", report.loadSpread());
    }

    private static void printComparison(Map<Strategy, PhaseReport> reports) {
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("  COMPARISON OF ALL FOUR ALGORITHMS");
        System.out.println("  Same cluster, same " + REQUESTS + " requests, same work per request.");
        System.out.println("=".repeat(100));
        System.out.println();
        System.out.printf("  %-24s %-14s %-14s %-14s %s%n",
                "Algorithm", "Makespan", "Avg latency", "p95 latency", "Split  (N1/N2/N3)");
        System.out.println("  " + "-".repeat(92));

        for (Map.Entry<Strategy, PhaseReport> e : reports.entrySet()) {
            PhaseReport r = e.getValue();
            Map<Integer, Integer> c = r.requestsPerNode();
            String split = c.getOrDefault(1, 0) + " / " + c.getOrDefault(2, 0)
                    + " / " + c.getOrDefault(3, 0);
            System.out.printf("  %-24s %-14s %-14s %-14s %s%n",
                    e.getKey().name().replace('_', ' '),
                    String.format("%.0f ms", r.makespanMillis()),
                    String.format("%.1f ms", r.averageLatency()),
                    String.format("%.1f ms", r.p95Latency()),
                    split);
        }

        PhaseReport rr = reports.get(Strategy.ROUND_ROBIN);
        PhaseReport best = null;
        for (PhaseReport r : reports.values()) {
            if (best == null || r.makespanMillis() < best.makespanMillis()) { best = r; }
        }
        System.out.println();
        if (rr != null && best != null && best.strategy() != Strategy.ROUND_ROBIN) {
            double gain = 100.0 * (rr.makespanMillis() - best.makespanMillis()) / rr.makespanMillis();
            System.out.printf("  %s finished the same work %.0f%% faster than ROUND ROBIN.%n",
                    best.strategy().name().replace('_', ' '), gain);
        }
        System.out.println();
        System.out.println("  Note the trap: ROUND ROBIN has the SMALLEST request-count spread, because it");
        System.out.println("  splits requests perfectly evenly. That even split is precisely why it is");
        System.out.println("  slowest - the weakest worker is handed just as much work as the strongest.");
        System.out.println("  Balanced request counts and balanced load are not the same thing.");
    }

    // ------------------------------------------------------------------ banner

    private static void printBanner() {
        System.out.println("=".repeat(100));
        System.out.println("   UNIFIED DISTRIBUTED COORDINATION FRAMEWORK");
        System.out.println("   EXPERIMENT 6  -  LOAD BALANCING");
        System.out.println("   Four algorithms over TCP  (standalone console demonstration)");
        System.out.println("=".repeat(100));
        System.out.println();
        System.out.println("The gateway receives every request and must choose a worker for it.");
        System.out.println("Four algorithms make that choice using different information:");
        System.out.println();
        System.out.println("   ROUND ROBIN            nothing at all - just take the next in turn");
        System.out.println("   WEIGHTED ROUND ROBIN   a capacity weight configured in advance");
        System.out.println("   LEAST CONNECTIONS      live count of requests still outstanding");
        System.out.println("   LEAST RESPONSE TIME    live count plus measured response history");
        System.out.println();
        System.out.println("The work is real SHA-256 hashing on the worker's Experiment 2 thread pool,");
        System.out.println("so a slow worker is slow because it genuinely computes more, not because");
        System.out.println("it sleeps. Requests are sent concurrently, which is what lets more than one");
        System.out.println("request be in flight and makes LEAST CONNECTIONS meaningful.");
        System.out.println();
    }

    private static void printCluster(List<WorkerNode> nodes) {
        System.out.printf("  %-8s %-10s %-10s %-14s %s%n",
                "Node", "Profile", "Port", "Pool threads", "Work multiplier");
        System.out.println("  " + "-".repeat(70));
        for (WorkerNode n : nodes) {
            System.out.printf("  %-8d %-10s %-10d %-14d x%d%n",
                    n.nodeId(), n.label(), n.port(), n.poolSize(), n.costFactor());
        }
        System.out.println();
        System.out.println("  Node 3 has one thread and does four times the computation per request,");
        System.out.println("  so it is roughly sixteen times weaker than Node 1. That gap is on purpose.");
    }
}
