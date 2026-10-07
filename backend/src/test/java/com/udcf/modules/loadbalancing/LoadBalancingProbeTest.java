package com.udcf.modules.loadbalancing;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import com.udcf.modules.multithreading.MultithreadingProperties;
import com.udcf.modules.multithreading.WorkloadType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;

/**
 * Opt-in measurement, never part of a normal build: runs all four strategies on the real
 * 5-node shape (FAST, MEDIUM, SLOW, MEDIUM, FAST) at Appendix B's 60 requests and 12 clients,
 * for several Exp 2 payload sizes, and prints what it measured. It asserts nothing about
 * which strategy wins: the result depends on the machine. Its purpose is to choose E6c's
 * default payload from data.
 *
 * <pre>
 * cd backend; .\mvnw.cmd test -Dtest=LoadBalancingProbeTest -Dudcf.loadbalancing.probe=true
 * </pre>
 *
 * <p>Test-only port bases 47120 to 47620 (requests ports 47521 to 47525), inside the Exp 6
 * test range 47100 to 47899.</p>
 */
@EnabledIfSystemProperty(named = "udcf.loadbalancing.probe", matches = "true")
class LoadBalancingProbeTest {

    private static final ClusterProperties CLUSTER = new ClusterProperties(5,
            List.of(FAST, MEDIUM, SLOW, MEDIUM, FAST),
            new ClusterProperties.Ports(47120, 47220, 47320, 47420, 47520, 47620));
    private static final MultithreadingProperties MULTITHREADING =
            new MultithreadingProperties(200, 60, "udcf-worker-", 30, 500, 2000,
                    new MultithreadingProperties.Backpressure(50, WorkloadType.CPU_HASH, 200));
    private static final int REQUESTS = 60;
    private static final int CLIENTS = 12;
    private static final int[] PAYLOADS = {25, 100, 400, 900};

    @Test
    @DisplayName("probe: four strategies x four payloads on the 5-node shape, printed, not asserted")
    void probe() {
        try (ClusterEventBus bus = new ClusterEventBus(new EventProperties(5000, 5000), java.time.Clock.systemUTC());
             Cluster cluster = new Cluster(CLUSTER, bus)) {
            LoadBalancingGateway gateway = new LoadBalancingGateway(cluster, MULTITHREADING,
                    new SimpleMeterRegistry(), bus, new LoadBalancingProperties(10_000));
            gateway.run(Strategy.ROUND_ROBIN, REQUESTS, 100, CLIENTS);   // JIT warm-up, discarded

            StringBuilder out = new StringBuilder();
            out.append(String.format(Locale.ROOT, "%nLoad balancing probe: %d requests, %d clients, nodes %s, %d cores%n",
                    REQUESTS, CLIENTS, CLUSTER.capacityPattern(), Runtime.getRuntime().availableProcessors()));
            out.append(String.format(Locale.ROOT, "%-7s %-21s %11s %8s %8s  %-20s %6s %8s%n",
                    "payload", "strategy", "makespan ms", "avg ms", "p95 ms", "per node 1/2/3/4/5", "spread", "failures"));
            for (int payload : PAYLOADS) {
                List<PhaseReport> reports = new ArrayList<>();
                for (Strategy strategy : Strategy.values()) {
                    PhaseReport r = gateway.run(strategy, REQUESTS, payload, CLIENTS);
                    reports.add(r);
                    out.append(String.format(Locale.ROOT, "%-7d %-21s %11.1f %8s %8s  %-20s %6d %8d%n",
                            payload, strategy, r.makespanMillis(), fmt(r.averageLatency()), fmt(r.p95Latency()),
                            split(r.requestsPerNode()), r.loadSpread(), r.failures()));
                }
                StrategyComparison c = new StrategyComparison(reports);
                out.append(String.format(Locale.ROOT, "        payload %d: round robin finished last = %s, most even = %s, fastest = %s%n",
                        payload, c.roundRobinFinishedLast(), c.roundRobinMostEven(),
                        c.fastest().map(PhaseReport::strategy).orElse(null)));
            }
            System.out.println(out);
        }
    }

    private static String fmt(OptionalDouble v) {
        return v.isPresent() ? String.format(Locale.ROOT, "%.1f", v.getAsDouble()) : "-";
    }

    private static String split(Map<Integer, Integer> perNode) {
        return perNode.values().stream().map(String::valueOf).collect(Collectors.joining("/"));
    }
}
