package com.udcf.scenario.scenarios;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeCapacity;
import com.udcf.modules.loadbalancing.LoadBalancingModule;
import com.udcf.modules.loadbalancing.Strategy;
import com.udcf.modules.loadbalancing.dto.ActionState;
import com.udcf.modules.loadbalancing.dto.CompareCommand;
import com.udcf.modules.loadbalancing.dto.ComparisonDto;
import com.udcf.modules.loadbalancing.dto.FindingDto;
import com.udcf.modules.loadbalancing.dto.NodeResultDto;
import com.udcf.modules.loadbalancing.dto.PhaseReportDto;
import com.udcf.scenario.Narration;
import com.udcf.scenario.RunMemory;
import com.udcf.scenario.Scenario;
import com.udcf.scenario.ScenarioProperties;
import com.udcf.scenario.ScenarioStep;
import com.udcf.scenario.StepContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * "A slow node under load": Experiment 6 compares all four strategies on the unequal cluster. Round
 * robin gives the slow node as many requests as the fast ones, so it finishes last. That is a measured
 * result: if round robin was not last, the step fails and shows the measured makespans.
 */
@Component
public class SlowNodeUnderLoadScenario implements Scenario {

    public static final String ID = "slow-node-under-load";
    static final String SLOW_NODE = "slowNode";
    static final String COMPARISON = "comparison";
    static final String MODULE = LoadBalancingModule.ID;

    private final LoadBalancingModule loadBalancing;
    private final Cluster cluster;
    private final ScenarioProperties properties;
    private final List<ScenarioStep> steps;

    public SlowNodeUnderLoadScenario(LoadBalancingModule loadBalancing, Cluster cluster,
                                     ScenarioProperties properties) {
        this.loadBalancing = loadBalancing;
        this.cluster = cluster;
        this.properties = properties;
        this.steps = List.of(
                ScenarioStep.of("The cluster is deliberately unequal: one node has fewer threads and more work per "
                        + "request than the others.", this::unequal),
                ScenarioStep.of("We send the same batch of requests with each of the four strategies, one after "
                        + "another, after one warm-up batch that is not counted.", this::compare),
                ScenarioStep.of("Round robin finishes last: it gives the slow node as many requests as the fast ones, "
                        + "and the whole batch waits for it.", this::roundRobinLast));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "A slow node under load";
    }

    @Override
    public String summary() {
        return "One node is much slower than the rest. Watch four load balancing strategies handle the same work, "
                + "and see which one gets held up by the slow node.";
    }

    @Override
    public List<String> modules() {
        return List.of("multithreading", "loadbalancing");
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public List<ScenarioStep> steps() {
        return steps;
    }

    @Override
    public String cleanup(RunMemory memory) {
        ComparisonDto latest = loadBalancing.overview().latestComparison();
        return latest != null && latest.state() == ActionState.RUNNING
                ? "The comparison is still running; Experiment 6 stays busy until it ends." : null;
    }

    // ------------------------------------------------------------------ steps

    private String unequal(StepContext step) {
        Map<String, String> capacities = new LinkedHashMap<>();
        for (ClusterNode node : cluster.nodes()) {
            capacities.put(String.valueOf(node.id()), node.capacity().name());
        }
        step.fact("capacities", capacities);
        List<ClusterNode> slow = cluster.nodes().stream().filter(n -> n.capacity() == NodeCapacity.SLOW).toList();
        if (slow.isEmpty()) {
            throw step.fail("No node is configured as slow, so this scenario cannot show the effect.");
        }
        ClusterNode node = slow.get(0);
        step.memory().remember(SLOW_NODE, node.id());
        step.fact("slowNodeId", node.id());
        step.fact("slowThreads", node.capacity().threads());
        step.fact("slowWorkMultiplier", node.capacity().workMultiplier());
        String others = cluster.nodes().stream().filter(n -> n.capacity() != NodeCapacity.SLOW)
                .collect(Collectors.groupingBy(ClusterNode::capacity, TreeMap::new,
                        Collectors.mapping(ClusterNode::id, Collectors.toList())))
                .entrySet().stream()
                .map(e -> Narration.nodes(e.getValue()) + " " + (e.getValue().size() == 1 ? "is " : "are ")
                        + e.getKey().name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
        return "Node " + node.id() + " is slow: " + node.capacity().threads() + " thread"
                + (node.capacity().threads() == 1 ? "" : "s") + " and " + node.capacity().workMultiplier()
                + " times the work per request" + (others.isEmpty() ? "." : "; " + others + ".");
    }

    private String compare(StepContext step) throws InterruptedException {
        var defaults = loadBalancing.overview().defaults();
        int workUnits = properties.slowNode().workUnits();
        long anchor = step.anchor(COMPARISON);
        ComparisonDto started = loadBalancing.startComparison(
                new CompareCommand(defaults.requestCount(), workUnits, defaults.concurrency()));
        String id = started.comparisonId();
        step.fact("comparisonId", id);
        step.fact("requestCount", started.requestCount());
        step.fact("workUnits", started.workUnits());
        step.fact("concurrency", started.concurrency());
        step.awaitEvent(anchor, MODULE, e -> id.equals(e.data().get("comparisonId"))
                && ("COMPARISON_FINISHED".equals(e.type()) || "RUN_FAILED".equals(e.type())),
                "the comparison to finish");
        ComparisonDto comparison = step.awaitState(() -> loadBalancing.overview().latestComparison(),
                c -> c != null && id.equals(c.comparisonId()) && c.state() != ActionState.RUNNING,
                "the comparison's result");
        if (comparison.state() == ActionState.FAILED) {
            throw step.fail("The comparison failed: " + comparison.error());
        }
        step.memory().remember(COMPARISON, comparison);
        int slow = step.memory().recall(SLOW_NODE, Integer.class);
        recordMeasurements(step, comparison, slow);
        return "Each strategy served " + started.requestCount() + " requests of " + started.workUnits()
                + " work units with " + started.concurrency() + " clients. Time to finish: " + makespans(comparison) + ".";
    }

    private String roundRobinLast(StepContext step) {
        ComparisonDto comparison = step.memory().recall(COMPARISON, ComparisonDto.class);
        int slow = step.memory().recall(SLOW_NODE, Integer.class);
        recordMeasurements(step, comparison, slow);
        FindingDto finding = comparison.finding();
        step.fact("roundRobinFinishedLast", finding.roundRobinFinishedLast());
        step.fact("fastest", finding.fastest() == null ? null : finding.fastest().name());
        step.fact("gainOverRoundRobinPercent", finding.gainOverRoundRobinPercent());
        PhaseReportDto roundRobin = phase(comparison, Strategy.ROUND_ROBIN);
        PhaseReportDto fastest = finding.fastest() == null ? null : phase(comparison, finding.fastest());
        if (!finding.roundRobinFinishedLast()) {
            throw step.fail("Round robin did not finish last this time: it took "
                    + Narration.millis(roundRobin.makespanMillis()) + " (" + makespans(comparison)
                    + "). The result is measured, not assumed.");
        }
        String gain = finding.gainOverRoundRobinPercent() == null || fastest == null ? ""
                : " " + Narration.sentence(label(finding.fastest())) + " finished "
                + Narration.percent(finding.gainOverRoundRobinPercent()) + " sooner.";
        return "Round robin finished last after " + Narration.millis(roundRobin.makespanMillis()) + ". It sent "
                + toNode(roundRobin, slow) + " of " + roundRobin.requestCount() + " requests to node " + slow + "; "
                + label(Strategy.LEAST_CONNECTIONS) + " sent it " + toNode(phase(comparison, Strategy.LEAST_CONNECTIONS),
                slow) + "." + gain;
    }

    // ------------------------------------------------------------------ measured values

    /** The four makespans and each strategy's requests to the slow node, as facts (in Strategy order). */
    private static void recordMeasurements(StepContext step, ComparisonDto comparison, int slow) {
        Map<String, Double> makespan = new LinkedHashMap<>();
        Map<String, Integer> toSlow = new LinkedHashMap<>();
        for (PhaseReportDto phase : comparison.phases()) {
            makespan.put(phase.strategy().name(), phase.makespanMillis());
            toSlow.put(phase.strategy().name(), toNode(phase, slow));
        }
        step.fact("slowNodeId", slow);
        step.fact("makespanMillis", makespan);
        step.fact("requestsToSlowNode", toSlow);
    }

    private static PhaseReportDto phase(ComparisonDto comparison, Strategy strategy) {
        return comparison.phases().stream().filter(p -> p.strategy() == strategy).findFirst()
                .orElseThrow(() -> new IllegalStateException("The comparison has no " + strategy + " phase"));
    }

    private static int toNode(PhaseReportDto phase, int nodeId) {
        return phase.nodes().stream().filter(n -> n.nodeId() == nodeId).mapToInt(NodeResultDto::requests).sum();
    }

    private static String makespans(ComparisonDto comparison) {
        return comparison.phases().stream()
                .map(p -> label(p.strategy()) + " " + Narration.millis(p.makespanMillis()))
                .collect(Collectors.joining(", "));
    }

    static String label(Strategy strategy) {
        return switch (Objects.requireNonNull(strategy)) {
            case ROUND_ROBIN -> "round robin";
            case WEIGHTED_ROUND_ROBIN -> "weighted round robin";
            case LEAST_CONNECTIONS -> "least connections";
            case LEAST_RESPONSE_TIME -> "least response time";
        };
    }
}
