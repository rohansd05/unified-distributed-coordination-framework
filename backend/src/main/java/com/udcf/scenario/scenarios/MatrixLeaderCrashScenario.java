package com.udcf.scenario.scenarios;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.RoleProvider;
import com.udcf.core.events.ClusterEvent;
import com.udcf.modules.matrix.ComputeRecord;
import com.udcf.modules.matrix.MatrixModule;
import com.udcf.modules.matrix.dto.MatrixRunCommand;
import com.udcf.modules.matrix.dto.MatrixRunDto;
import com.udcf.modules.matrix.dto.MatrixVerificationDto;
import com.udcf.modules.mpi.CollectiveOutcome;
import com.udcf.modules.mpi.dto.RootSource;
import com.udcf.scenario.EventData;
import com.udcf.scenario.Narration;
import com.udcf.scenario.RunMemory;
import com.udcf.scenario.Scenario;
import com.udcf.scenario.ScenarioStep;
import com.udcf.scenario.StepContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * "The leader dies during a matrix multiply": Experiment 10 multiplies at its largest size with the
 * leader elected by Experiment 4 as root; the leader node crashes as soon as the run is seen to start;
 * the first run's real outcome is reported; after the re-election the same multiply runs again with the
 * new leader as root, and its result must match the sequential product.
 *
 * <p><b>Mid-run is observed, not assumed.</b> No existing parameter makes a run last long: the largest
 * size (floor(sqrt(udcf.mpi.max-payload-elements)), 316 locally) takes a fraction of a second. The first
 * run is therefore started on its own thread, and the crash follows the first {@code MATRIX_RUN_STARTED}
 * at once. Whether the crash really came before the run ended is then read from the bus order
 * ({@code NODE_CRASHED} against {@code MATRIX_RUN_FINISHED}) and stated in the step's message.</p>
 *
 * <p>Whatever the first run's outcome (complete, partial, failed or an error), its step passes when it is
 * reported; the scenario passes when the second run, rooted at the new leader, verifies.</p>
 */
@Component
public class MatrixLeaderCrashScenario implements Scenario {

    public static final String ID = "matrix-leader-crash";

    static final String MODULE = MatrixModule.ID;
    static final String CLUSTER = "cluster";
    static final String FIRST_RUN = "firstRun";
    static final String ROOT = "root";
    static final String SIZE = "size";
    static final String SEED = "seed";
    static final String NEW_LEADER = "newLeader";
    static final String FIRST_RESULT = "firstResult";
    static final String START = "start";
    static final String CRASH = "crash";
    static final String RUNNER_THREAD = "udcf-scenario-matrix";
    /** A run is bounded by the collective deadline plus one transfer (about 22 s locally; E9c), so allow more. */
    static final Duration RUN_END_DEADLINE = Duration.ofSeconds(60);

    private final MatrixModule matrix;
    private final RoleProvider roles;
    private final Cluster cluster;
    private final List<ScenarioStep> steps;

    public MatrixLeaderCrashScenario(MatrixModule matrix, RoleProvider roles, Cluster cluster) {
        this.matrix = Objects.requireNonNull(matrix, "matrix must not be null");
        this.roles = Objects.requireNonNull(roles, "roles must not be null");
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        this.steps = List.of(
                ScenarioStep.of("We start Experiment 10's largest matrix multiply without naming a root, so the root "
                        + "is the leader elected by Experiment 4.", this::startFirstRun),
                ScenarioStep.of("We crash the leader, the root of the multiply, as soon as the multiply has started.",
                        this::crashRoot),
                new ScenarioStep("We wait for the first multiply to end and report what really happened to it.",
                        RUN_END_DEADLINE, this::firstOutcome),
                ScenarioStep.of("Experiment 4 elects a new leader among the live nodes.", this::newLeader),
                new ScenarioStep("We run the same multiply again. Its root is now the new leader, and the result is "
                        + "checked against the sequential product.", RUN_END_DEADLINE, this::secondRun));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "The leader dies during a matrix multiply";
    }

    @Override
    public String summary() {
        return "A parallel matrix multiply runs from the leader when the leader crashes. See what happens to that "
                + "run, then run it again from the newly elected leader and check the result.";
    }

    @Override
    public List<String> modules() {
        return List.of("election", "rmi", "mpi", "matrix");
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public List<ScenarioStep> steps() {
        return steps;
    }

    @Override
    public String cleanup(RunMemory memory) {
        return memory.find(FIRST_RUN, CompletableFuture.class).filter(run -> !run.isDone()).isPresent()
                ? "The first multiply is still running; Experiment 10 stays busy until it ends." : null;
    }

    // ------------------------------------------------------------------ steps

    private String startFirstRun(StepContext step) throws InterruptedException {
        int size = matrix.overview().limits().maxSize();
        CompletableFuture<MatrixRunDto> first = new CompletableFuture<>();
        long anchor = step.anchor(START);
        Thread runner = new Thread(() -> {
            try {
                first.complete(matrix.run(new MatrixRunCommand(size, null, null)));
            } catch (Throwable e) {
                first.completeExceptionally(e);
            }
        }, RUNNER_THREAD);
        runner.setDaemon(true);
        step.memory().remember(FIRST_RUN, first);
        runner.start();
        ClusterEvent started;
        try {
            started = step.awaitEvent(anchor, MODULE, "MATRIX_RUN_STARTED", e -> true, "the multiply to start");
        } catch (RuntimeException timedOut) {
            if (first.isCompletedExceptionally()) {
                throw step.fail("The multiply did not start: " + failure(first) + ".");
            }
            throw timedOut;
        }
        int root = started.nodeId();
        Integer leader = roles.leaderId().orElse(null);
        Long seed = EventData.longValue(started, "seed");
        step.fact("matrixRunId", EventData.longValue(started, "runId"));
        step.fact("size", size);
        step.fact("seed", seed);
        step.fact("rootNodeId", root);
        step.fact("rootSource", started.data().get("rootSource"));
        step.fact("leaderId", leader);
        step.fact("liveNodeIds", started.data().get("liveNodeIds"));
        step.fact("startedSequence", started.sequence());
        step.memory().remember(ROOT, root);
        step.memory().remember(SIZE, size);
        step.memory().remember(SEED, seed);
        if (!Integer.valueOf(root).equals(leader) || !RootSource.LEADER.name().equals(started.data().get("rootSource"))) {
            throw step.fail("Node " + root + " is the root (" + started.data().get("rootSource") + "), but the leader is "
                    + (leader == null ? "nobody" : "node " + leader) + ".");
        }
        return "Node " + root + ", the leader elected by Experiment 4, started run " + started.data().get("runId")
                + " of size " + size + " (seed " + seed + ") over " + Narration.nodes(liveIds(started)) + ".";
    }

    private String crashRoot(StepContext step) throws InterruptedException {
        int root = step.memory().recall(ROOT, Integer.class);
        CompletableFuture<?> first = step.memory().recall(FIRST_RUN, CompletableFuture.class);
        boolean runningBefore = !first.isDone();
        long anchor = step.anchor(CRASH);
        boolean crashed = cluster.crash(root);
        step.fact("crashedNodeId", root);
        step.fact("runStillRunningAtCrash", runningBefore);
        if (!crashed) {
            throw step.fail("Node " + root + " was already down, so it could not be crashed.");
        }
        ClusterEvent event = step.awaitEvent(anchor, CLUSTER, "NODE_CRASHED", e -> e.nodeId() == root,
                "node " + root + "'s crash to be recorded");
        step.fact("crashedSequence", event.sequence());
        return "Node " + root + " went down on every protocol (event " + event.sequence() + ")"
                + (runningBefore ? ", while the multiply was still running." : "; the multiply had already returned.");
    }

    private String firstOutcome(StepContext step) throws InterruptedException {
        int root = step.memory().recall(ROOT, Integer.class);
        CompletableFuture<?> first = step.memory().recall(FIRST_RUN, CompletableFuture.class);
        step.awaitState(first::isDone, done -> done, "the first multiply to end");
        long crashAnchor = step.anchorOf(CRASH);
        if (first.isCompletedExceptionally()) {
            String error = failure(first);
            step.fact("outcome", "ERROR");
            step.fact("error", error);
            return "The first multiply ended with an error: " + error + ".";
        }
        MatrixRunDto run = (MatrixRunDto) first.join();
        step.memory().remember(FIRST_RESULT, run);
        ClusterEvent finished = step.awaitEvent(step.anchorOf(START), MODULE,
                e -> EventData.is(e, "runId", run.runId())
                        && ("MATRIX_RUN_FINISHED".equals(e.type()) || "MATRIX_RUN_REFUSED".equals(e.type())),
                "the first multiply's outcome event");
        long crashed = crashedEvent(step, root, crashAnchor).sequence();
        MatrixVerificationDto verification = run.verification();
        step.fact("matrixRunId", run.runId());
        step.fact("outcome", run.outcome() == null ? null : run.outcome().name());
        step.fact("computesDone", count(run, ComputeRecord.Status.DONE));
        step.fact("computes", run.computes().size());
        step.fact("passed", verification == null ? null : verification.passed());
        step.fact("blocksMissing", verification == null ? null : verification.blocksMissing());
        step.fact("sequentialChecksum", verification == null ? null : verification.sequentialChecksum());
        step.fact("finishedSequence", finished.sequence());
        step.fact("crashAnchorSequence", crashAnchor);
        step.fact("crashedSequence", crashed);
        String timing;
        if (finished.sequence() <= crashAnchor) {
            timing = "It had already ended before the crash began (event " + finished.sequence() + ").";
            step.fact("crashedMidRun", false);
        } else if (finished.sequence() < crashed) {
            timing = "It ended (event " + finished.sequence() + ") while node " + root + " was going down (event "
                    + crashed + ").";
            step.fact("crashedMidRun", true);
        } else {
            timing = "Node " + root + " was down (event " + crashed + ") before it ended (event "
                    + finished.sequence() + ").";
            step.fact("crashedMidRun", true);
        }
        if (run.refusal() != null) {
            return "The first multiply was refused: " + run.refusal().message() + " " + timing;
        }
        return "The first multiply ended " + run.outcome().name().toLowerCase(Locale.ROOT) + ": "
                + count(run, ComputeRecord.Status.DONE) + " of " + run.computes().size() + " blocks computed; "
                + verdict(verification) + ". " + timing;
    }

    private String newLeader(StepContext step) throws InterruptedException {
        int root = step.memory().recall(ROOT, Integer.class);
        ClusterEvent changed = step.awaitEvent(step.anchorOf(CRASH), CLUSTER, "LEADER_CHANGED", e -> {
            Integer id = EventData.integer(e, "leaderId");
            return id != null && id != root;
        }, "Experiment 4 to elect a new leader");
        int newLeader = EventData.integer(changed, "leaderId");
        step.fact("newLeaderId", newLeader);
        step.fact("leaderChangedSequence", changed.sequence());
        step.memory().remember(NEW_LEADER, newLeader);
        return "Experiment 4 elected node " + newLeader + " (event " + changed.sequence() + ").";
    }

    private String secondRun(StepContext step) {
        int root = step.memory().recall(ROOT, Integer.class);
        int newLeader = step.memory().recall(NEW_LEADER, Integer.class);
        int size = step.memory().recall(SIZE, Integer.class);
        Long seed = step.memory().find(SEED, Long.class).orElse(null);
        MatrixRunDto run = matrix.run(new MatrixRunCommand(size, seed, null));
        MatrixVerificationDto verification = run.verification();
        step.fact("matrixRunId", run.runId());
        step.fact("rootNodeId", run.rootNodeId());
        step.fact("rootSource", run.rootSource() == null ? null : run.rootSource().name());
        step.fact("skippedNodeIds", run.skippedNodeIds());
        step.fact("outcome", run.outcome() == null ? null : run.outcome().name());
        step.fact("passed", verification == null ? null : verification.passed());
        step.fact("checksumsMatch", verification == null ? null : verification.checksumsMatch());
        step.fact("parallelChecksum", verification == null ? null : verification.parallelChecksum());
        if (run.refusal() != null) {
            throw step.fail("The second multiply was refused: " + run.refusal().message());
        }
        if (run.rootNodeId() != newLeader || run.rootSource() != RootSource.LEADER) {
            throw step.fail("Node " + run.rootNodeId() + " was the root (" + run.rootSource() + "), not the new leader, "
                    + "node " + newLeader + ".");
        }
        if (run.outcome() != CollectiveOutcome.COMPLETE || verification == null
                || !Boolean.TRUE.equals(verification.passed())) {
            throw step.fail("The second multiply ended " + run.outcome().name().toLowerCase(Locale.ROOT) + ": "
                    + verdict(verification) + ".");
        }
        MatrixRunDto first = step.memory().find(FIRST_RESULT, MatrixRunDto.class).orElse(null);
        String firstChecksum = first == null || first.verification() == null ? null
                : first.verification().sequentialChecksum();
        boolean sameProduct = firstChecksum != null && firstChecksum.equals(verification.sequentialChecksum());
        step.fact("sameProductAsFirstRun", firstChecksum == null ? null : sameProduct);
        return "Node " + newLeader + ", the new leader, was the root of run " + run.runId() + " over "
                + Narration.nodes(run.rankNodeIds()) + (run.skippedNodeIds().contains(root)
                ? " (node " + root + " skipped: down)" : "") + ". It completed, and its checksum "
                + shortChecksum(verification.parallelChecksum()) + " matches the sequential product"
                + (sameProduct ? ", the same product the first run was checked against." : ".");
    }

    // ------------------------------------------------------------------ helpers

    private ClusterEvent crashedEvent(StepContext step, int root, long crashAnchor) throws InterruptedException {
        return step.awaitEvent(crashAnchor, CLUSTER, "NODE_CRASHED", e -> e.nodeId() == root,
                "node " + root + "'s crash to be recorded");
    }

    private static List<Integer> liveIds(ClusterEvent started) {
        return started.data().get("liveNodeIds") instanceof List<?> ids
                ? ids.stream().filter(Number.class::isInstance).map(id -> ((Number) id).intValue()).toList()
                : List.of();
    }

    private static long count(MatrixRunDto run, ComputeRecord.Status status) {
        return run.computes().stream().filter(c -> c.status() == status).count();
    }

    private static String verdict(MatrixVerificationDto verification) {
        if (verification == null) {
            return "the result was not checked";
        }
        if (verification.passed() == null) {
            return "the result could not be checked (" + verification.blocksMissing() + " of the blocks missing)";
        }
        return verification.passed() ? "the result matched the sequential product"
                : "the result did not match the sequential product (" + verification.mismatchedElements()
                + " elements differ)";
    }

    private static String shortChecksum(String checksum) {
        return checksum == null ? "(none)" : checksum.substring(0, Math.min(12, checksum.length()));
    }

    private static String failure(CompletableFuture<?> run) {
        try {
            run.get();
            return "no error";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }
}
