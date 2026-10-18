package com.udcf.scenario.scenarios;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.RoleProvider;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.EventLogExporter;
import com.udcf.modules.mapreduce.InputType;
import com.udcf.modules.mapreduce.LogFields;
import com.udcf.modules.mapreduce.MapReduceModule;
import com.udcf.modules.mapreduce.dto.MapReduceOverviewDto;
import com.udcf.modules.mapreduce.dto.ResultRowDto;
import com.udcf.modules.mapreduce.dto.RunCommand;
import com.udcf.modules.mapreduce.dto.RunDto;
import com.udcf.modules.mapreduce.dto.RunState;
import com.udcf.modules.mapreduce.dto.UploadDto;
import com.udcf.scenario.EventData;
import com.udcf.scenario.Narration;
import com.udcf.scenario.RunMemory;
import com.udcf.scenario.Scenario;
import com.udcf.scenario.ScenarioStep;
import com.udcf.scenario.StepContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * "Analyse the incident with MapReduce": a first job gives Experiment 7 a coordinator (the leader);
 * the leader node crashes; Experiment 4 elects a new leader and Experiment 7 follows it
 * ({@code COORDINATOR_CHANGED}); the live event log is exported (link L5) and Experiment 7 counts the
 * exported events by type and by node, coordinated by the new leader.
 *
 * <p><b>How the two counts are made.</b> Experiment 7 has no job that counts by node, and its job
 * registry belongs to its module. The scenario therefore takes one snapshot of the event log through
 * {@link EventLogExporter} (the L5 export) and runs Experiment 7's own {@code event-category-count} job
 * twice on it as an upload: once on the exported lines unchanged (each event counted under its type),
 * and once on the same events written as {@code node=N | category=node N} (each event counted under its
 * node). Both jobs see the same snapshot, so each total must equal the number of exported lines.</p>
 *
 * <p>Every number in a message comes from the export or the job results; the narration has none.</p>
 */
@Component
public class MapReduceIncidentScenario implements Scenario {

    public static final String ID = "mapreduce-incident";

    static final String MODULE = MapReduceModule.ID;
    static final String CLUSTER = "cluster";
    static final String WARM_UP_JOB = "word-count";
    static final String COUNT_JOB = "event-category-count";
    static final String LEADER_CHANGED = "LEADER_CHANGED";
    /** Rows named in the closing narration, per dimension. */
    static final int TOP = 3;

    static final String LEADER = "leader";
    static final String NEW_LEADER = "newLeader";
    static final String LEADER_CHANGED_EVENT = "leaderChangedEvent";
    static final String EXPORT = "export";
    static final String BY_TYPE = "byType";
    static final String BY_NODE = "byNode";
    static final String CRASH = "crash";

    private final MapReduceModule mapReduce;
    private final EventLogExporter exporter;
    private final RoleProvider roles;
    private final Cluster cluster;
    private final List<ScenarioStep> steps;

    public MapReduceIncidentScenario(MapReduceModule mapReduce, EventLogExporter exporter, RoleProvider roles,
                                     Cluster cluster) {
        this.mapReduce = Objects.requireNonNull(mapReduce, "mapReduce must not be null");
        this.exporter = Objects.requireNonNull(exporter, "exporter must not be null");
        this.roles = Objects.requireNonNull(roles, "roles must not be null");
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        this.steps = List.of(
                ScenarioStep.of("We run a first MapReduce job. Experiment 4 elects a leader, and that leader "
                        + "coordinates the job.", this::firstJob),
                ScenarioStep.of("We crash the leader, on every protocol at once.", this::crashLeader),
                ScenarioStep.of("Experiment 4 elects a new leader, and Experiment 7 names it the coordinator of the "
                        + "next job.", this::newCoordinator),
                ScenarioStep.of("We export the live event log, the text every module's events become for "
                        + "Experiment 7.", this::export),
                ScenarioStep.of("MapReduce counts the exported events by type, coordinated by the new leader.",
                        this::countByType),
                ScenarioStep.of("MapReduce counts the same events by node, again coordinated by the new leader.",
                        this::countByNode),
                ScenarioStep.of("We read the largest counts from the results.", this::topCounts));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Analyse the incident with MapReduce";
    }

    @Override
    public String summary() {
        return "The leader crashes and a new one takes over. Then the cluster analyses its own event log: "
                + "MapReduce, coordinated by the new leader, counts what happened by event type and by node.";
    }

    @Override
    public List<String> modules() {
        return List.of("election", "mapreduce");
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public List<ScenarioStep> steps() {
        return steps;
    }

    @Override
    public String cleanup(RunMemory memory) {
        MapReduceOverviewDto overview = mapReduce.overview();
        return overview.currentAction() == null ? null
                : "A MapReduce job is still running; Experiment 7 stays busy until it ends.";
    }

    // ------------------------------------------------------------------ steps

    private String firstJob(StepContext step) throws InterruptedException {
        RunDto run = runToTheEnd(step, "firstJob", new RunCommand(WARM_UP_JOB, InputType.SAMPLE, null, null));
        Integer leader = roles.leaderId().orElse(null);
        step.fact("leaderId", leader);
        if (!Objects.equals(run.coordinatorId(), leader)) {
            throw step.fail("Node " + run.coordinatorId() + " coordinated the job, but the leader is "
                    + (leader == null ? "nobody" : "node " + leader) + ".");
        }
        step.memory().remember(LEADER, leader);
        return "Node " + leader + ", the leader elected by Experiment 4, coordinated the " + run.jobTitle()
                .toLowerCase(Locale.ROOT) + " job over " + Narration.nodes(run.workerIds()) + " in "
                + Narration.millis(run.report().timings().totalMillis()) + ".";
    }

    private String crashLeader(StepContext step) throws InterruptedException {
        int leader = step.memory().recall(LEADER, Integer.class);
        long anchor = step.anchor(CRASH);
        boolean crashed = cluster.crash(leader);
        step.fact("crashedNodeId", leader);
        if (!crashed) {
            throw step.fail("Node " + leader + " was already down, so it could not be crashed.");
        }
        ClusterEvent event = step.awaitEvent(anchor, CLUSTER, "NODE_CRASHED", e -> e.nodeId() == leader,
                "node " + leader + "'s crash to be recorded");
        step.fact("crashedSequence", event.sequence());
        return "Node " + leader + " went down on every protocol (event " + event.sequence() + ").";
    }

    private String newCoordinator(StepContext step) throws InterruptedException {
        int leader = step.memory().recall(LEADER, Integer.class);
        long crash = step.anchorOf(CRASH);
        ClusterEvent changed = step.awaitEvent(crash, CLUSTER, LEADER_CHANGED, e -> {
            Integer id = EventData.integer(e, "leaderId");
            return id != null && id != leader;
        }, "Experiment 4 to elect a new leader");
        int newLeader = EventData.integer(changed, "leaderId");
        step.fact("newLeaderId", newLeader);
        ClusterEvent coordinator = step.awaitEvent(crash, MODULE, "COORDINATOR_CHANGED",
                e -> e.nodeId() == newLeader, "Experiment 7 to name node " + newLeader + " its coordinator");
        step.fact("previousCoordinatorId", EventData.integer(coordinator, "previousNodeId"));
        step.fact("cause", coordinator.data().get("cause"));
        step.requireOrder("leaderChanged", changed, "coordinatorChanged", coordinator);
        step.memory().remember(NEW_LEADER, newLeader);
        step.memory().remember(LEADER_CHANGED_EVENT, changed);
        return "Experiment 4 elected node " + newLeader + " (event " + changed.sequence() + "), and Experiment 7 "
                + "named it the coordinator in place of node " + leader + " (event " + coordinator.sequence() + ").";
    }

    private String export(StepContext step) {
        ClusterEvent changed = step.memory().recall(LEADER_CHANGED_EVENT, ClusterEvent.class);
        MapReduceOverviewDto overview = mapReduce.overview();
        List<String> all = exporter.lines(null, null, overview.limits().eventLogMaxEvents());
        List<String> lines = newestThatFit(all, overview.limits().uploadMaxBytes());
        int bytes = text(lines).getBytes(StandardCharsets.UTF_8).length;
        Export export = new Export(lines, count(lines, line -> LogFields.field(line, "category")),
                count(lines, MapReduceIncidentScenario::nodeKey));
        step.fact("exportedLines", lines.size());
        step.fact("exportedBytes", bytes);
        step.fact("leftOutLines", all.size() - lines.size());
        step.fact("leaderChangedSequence", changed.sequence());
        boolean seen = lines.stream().anyMatch(line -> LEADER_CHANGED.equals(LogFields.field(line, "category"))
                && String.valueOf(changed.sequence()).equals(LogFields.field(line, "seq")));
        step.fact("leaderChangedExported", seen);
        if (!seen) {
            throw step.fail("The export of " + lines.size() + " lines does not contain event " + changed.sequence()
                    + ", the LEADER_CHANGED that elected node " + step.memory().recall(NEW_LEADER, Integer.class) + ".");
        }
        step.memory().remember(EXPORT, export);
        int left = all.size() - lines.size();
        return "Exported " + lines.size() + " events (" + bytes + " bytes) in causal order, including event "
                + changed.sequence() + ", the LEADER_CHANGED that elected node "
                + step.memory().recall(NEW_LEADER, Integer.class) + "."
                + (left == 0 ? "" : " The " + left + " oldest lines did not fit Experiment 7's upload limit.");
    }

    private String countByType(StepContext step) throws InterruptedException {
        Export export = step.memory().recall(EXPORT, Export.class);
        RunDto run = countJob(step, "byType", "incident-events-by-type.txt", export.lines());
        Map<String, Long> counts = counts(run);
        checkCounts(step, counts, export.byType(), "type");
        long leaderChanged = counts.getOrDefault(LEADER_CHANGED, 0L);
        step.fact("leaderChangedCount", leaderChanged);
        if (leaderChanged < 1) {
            throw step.fail("The job counted no LEADER_CHANGED event, but the export has "
                    + export.byType().getOrDefault(LEADER_CHANGED, 0L) + ".");
        }
        step.memory().remember(BY_TYPE, counts);
        return "Node " + run.coordinatorId() + " coordinated the count over " + Narration.nodes(run.workerIds())
                + ": " + counts.size() + " event types in " + export.lines().size() + " events. LEADER_CHANGED appears "
                + leaderChanged + " times, the election just seen included.";
    }

    private String countByNode(StepContext step) throws InterruptedException {
        Export export = step.memory().recall(EXPORT, Export.class);
        List<String> keyed = export.lines().stream()
                .map(line -> "node=" + LogFields.field(line, "node") + " | category=" + nodeKey(line)).toList();
        RunDto run = countJob(step, "byNode", "incident-events-by-node.txt", keyed);
        Map<String, Long> counts = counts(run);
        checkCounts(step, counts, export.byNode(), "node");
        step.memory().remember(BY_NODE, counts);
        return "Node " + run.coordinatorId() + " coordinated the count again: the " + export.lines().size()
                + " events came from " + counts.size() + " sources (node 0 is the cluster itself).";
    }

    private String topCounts(StepContext step) {
        @SuppressWarnings("unchecked")
        Map<String, Long> byType = step.memory().recall(BY_TYPE, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Long> byNode = step.memory().recall(BY_NODE, Map.class);
        int crashed = step.memory().recall(LEADER, Integer.class);
        Map<String, Long> topTypes = top(byType);
        Map<String, Long> topNodes = top(byNode);
        step.fact("topTypes", topTypes);
        step.fact("topNodes", topNodes);
        step.fact("crashedNodeEvents", byNode.getOrDefault("node " + crashed, 0L));
        return "Most frequent types: " + phrase(topTypes) + ". Busiest sources: " + phrase(topNodes)
                + ". Node " + crashed + ", the crashed leader, logged " + byNode.getOrDefault("node " + crashed, 0L)
                + " events before it went down.";
    }

    // ------------------------------------------------------------------ jobs

    /** Starts a job and waits until Experiment 7 records it as finished; it must have COMPLETED. */
    private RunDto runToTheEnd(StepContext step, String name, RunCommand command) throws InterruptedException {
        long anchor = step.anchor(name);
        String runId = mapReduce.startRun(command).runId();
        step.fact("jobRunId", runId);
        step.awaitEvent(anchor, MODULE, e -> runId.equals(e.data().get("runId"))
                && ("JOB_COMPLETED".equals(e.type()) || "JOB_FAILED".equals(e.type())), "job " + runId + " to finish");
        RunDto run = step.awaitState(() -> mapReduce.run(runId), r -> r.state() != RunState.RUNNING,
                "job " + runId + "'s result");
        step.fact("coordinatorId", run.coordinatorId());
        step.fact("workerIds", run.workerIds());
        if (run.state() != RunState.COMPLETED) {
            throw step.fail("Job " + runId + " failed: " + run.error());
        }
        step.fact("totalMillis", run.report().timings().totalMillis());
        return run;
    }

    /** One count over uploaded lines; the coordinator must be the new leader. */
    private RunDto countJob(StepContext step, String name, String fileName, List<String> lines)
            throws InterruptedException {
        int newLeader = step.memory().recall(NEW_LEADER, Integer.class);
        String base64 = Base64.getEncoder().encodeToString(text(lines).getBytes(StandardCharsets.UTF_8));
        RunDto run = runToTheEnd(step, name, new RunCommand(COUNT_JOB, InputType.UPLOAD,
                new UploadDto(fileName, "text/plain", base64), null));
        if (!Objects.equals(run.coordinatorId(), newLeader)) {
            throw step.fail("Node " + run.coordinatorId() + " coordinated the job, not the new leader, node "
                    + newLeader + ".");
        }
        if (run.report().resultsTruncated()) {
            throw step.fail("Experiment 7 kept only " + run.report().results().size() + " of "
                    + run.report().resultKeys() + " result rows, so the counts cannot be checked.");
        }
        return run;
    }

    /** The job's counts must equal the export's, key for key. */
    private static void checkCounts(StepContext step, Map<String, Long> counted, Map<String, Long> expected,
                                    String dimension) {
        long total = counted.values().stream().mapToLong(Long::longValue).sum();
        step.fact("resultKeys", counted.size());
        step.fact("totalCounted", total);
        if (!counted.equals(expected)) {
            throw step.fail("The counts by " + dimension + " differ from the export: " + total + " events counted under "
                    + counted.size() + " keys, " + expected.values().stream().mapToLong(Long::longValue).sum()
                    + " exported under " + expected.size() + ".");
        }
    }

    private static Map<String, Long> counts(RunDto run) {
        Map<String, Long> counts = new TreeMap<>();
        for (ResultRowDto row : run.report().results()) {
            counts.put(row.key(), row.count());
        }
        return counts;
    }

    // ------------------------------------------------------------------ helpers

    /** The export snapshot: its lines and the counts the jobs must reproduce. */
    record Export(List<String> lines, Map<String, Long> byType, Map<String, Long> byNode) {
    }

    /** "node 4" for an exported line of node 4. */
    static String nodeKey(String line) {
        return "node " + LogFields.field(line, "node");
    }

    /** The newest lines whose text (one line feed each) fits {@code maxBytes}, in their original order. */
    static List<String> newestThatFit(List<String> lines, int maxBytes) {
        List<String> kept = new ArrayList<>();
        long bytes = 0;
        for (int i = lines.size() - 1; i >= 0; i--) {
            bytes += lines.get(i).getBytes(StandardCharsets.UTF_8).length + 1;
            if (bytes > maxBytes) {
                break;
            }
            kept.add(0, lines.get(i));
        }
        return kept;
    }

    static String text(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        return text.toString();
    }

    static Map<String, Long> count(List<String> lines, Function<String, String> key) {
        return lines.stream().collect(Collectors.groupingBy(key, TreeMap::new, Collectors.counting()));
    }

    /** The {@value #TOP} largest counts, largest first; ties by key. */
    static Map<String, Long> top(Map<String, Long> counts) {
        Map<String, Long> top = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP).forEach(e -> top.put(e.getKey(), e.getValue()));
        return top;
    }

    /** "TASK_SENT 120, TASK_COMPLETED 118 and LEADER_CHANGED 3". */
    static String phrase(Map<String, Long> counts) {
        List<String> parts = counts.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList();
        if (parts.size() <= 1) {
            return parts.isEmpty() ? "none" : parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }
}
