package com.udcf.scenario;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.ClusterResetService;
import com.udcf.core.cluster.NodeRole;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.scenario.dto.EndStateDto;
import com.udcf.scenario.dto.EndStateNodeDto;
import com.udcf.scenario.dto.RunAcceptedDto;
import com.udcf.scenario.dto.RunDto;
import com.udcf.scenario.dto.RunState;
import com.udcf.scenario.dto.ScenarioDto;
import com.udcf.scenario.dto.StepDefinitionDto;
import com.udcf.scenario.dto.StepState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Runs guided scenarios on the real cluster, one at a time, and reports every step with what it
 * observed (Phase 9A.6).
 *
 * <p><b>Scenarios.</b> Every {@link Scenario} bean, sorted by {@link Scenario#order()}; ids must be
 * unique. Adding one is add-only.</p>
 *
 * <p><b>A run.</b> {@link #start(String)} answers at once; the run executes on one daemon thread,
 * {@value #RUNNER_THREAD}. That thread is a request-like thread (never a socket listener, the event
 * dispatcher or {@code udcf-roles-dispatcher}), so steps may call module actions that block, such as
 * {@code RoleProvider.ensureLeader()}. The run:</p>
 * <ol>
 *   <li>resets the cluster to a clean slate, retrying while a module is BUSY (HTTP 409 otherwise),
 *       for at most {@code udcf.scenario.reset-timeout-millis}; if it cannot, the run is FAILED and
 *       every step SKIPPED;</li>
 *   <li>runs the steps in order; a step's waits share its deadline. A step that fails, times out or
 *       throws is FAILED with its message, and every later step is SKIPPED;</li>
 *   <li>always runs the scenario's {@link Scenario#cleanup cleanup} in a finally, then records the
 *       end state read from the cluster. There is no automatic reset afterwards.</li>
 * </ol>
 * <p>A run never stays RUNNING: any error ends it FAILED. If someone else resets the cluster during a
 * run, the waiting step fails ("The cluster was reset during the scenario"). There is no cancel.</p>
 *
 * <p><b>Events</b> (module {@value #MODULE}, node 0, one tick of the cluster clock):
 * {@code SCENARIO_STARTED} {runId, scenarioId, title, steps}; {@code STEP_STARTED} {runId,
 * scenarioId, stepIndex, narration}; {@code STEP_PASSED} and {@code STEP_FAILED} {runId, scenarioId,
 * stepIndex, durationMs, facts}; {@code STEP_SKIPPED} {runId, scenarioId, stepIndex};
 * {@code SCENARIO_FINISHED} {runId, scenarioId, title, outcome, verdict}. Step indexes are 1-based.</p>
 *
 * <p>The last {@code udcf.scenario.history-limit} runs are kept in memory.</p>
 */
@Component
public class ScenarioEngine implements AutoCloseable {

    public static final String MODULE = "scenario";
    static final String RUNNER_THREAD = "udcf-scenario-runner";
    private static final long CLOSE_WAIT_SECONDS = 5;
    private static final Logger log = LoggerFactory.getLogger(ScenarioEngine.class);

    private final List<Scenario> scenarios;
    private final Cluster cluster;
    private final ClusterEventBus bus;
    private final Runnable reset;
    private final ScenarioProperties properties;
    private final Clock clock;
    private final LongSupplier nanoClock;
    private final ExecutorService runner;
    private final Object lock = new Object();

    // guarded by lock
    private final Deque<ScenarioRun> history = new ArrayDeque<>();
    private ScenarioRun current;
    private long lastRunId;

    @Autowired
    public ScenarioEngine(ObjectProvider<Scenario> scenarios, Cluster cluster, ClusterEventBus bus,
                          ClusterResetService resetService, ScenarioProperties properties, Clock clock) {
        this(scenarios.orderedStream().toList(), cluster, bus, resetService::reset, properties, clock,
                System::nanoTime);
    }

    /** For tests: chosen scenarios, a chosen reset and nano clock. */
    ScenarioEngine(List<Scenario> scenarios, Cluster cluster, ClusterEventBus bus, Runnable reset,
                   ScenarioProperties properties, Clock clock, LongSupplier nanoClock) {
        this.scenarios = validated(scenarios);
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.reset = Objects.requireNonNull(reset, "reset must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock must not be null");
        this.runner = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, RUNNER_THREAD);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static List<Scenario> validated(List<Scenario> scenarios) {
        Set<String> ids = new HashSet<>();
        for (Scenario scenario : scenarios) {
            if (scenario.id() == null || scenario.id().isBlank()) {
                throw new IllegalStateException("Scenario " + scenario.getClass().getName() + " has a blank id");
            }
            if (!ids.add(scenario.id())) {
                throw new IllegalStateException("Duplicate scenario id '" + scenario.id() + "'");
            }
            if (scenario.steps().isEmpty()) {
                throw new IllegalStateException("Scenario '" + scenario.id() + "' has no steps");
            }
        }
        return scenarios.stream().sorted(Comparator.comparingInt(Scenario::order)).toList();
    }

    // ------------------------------------------------------------------ reads

    /** Every scenario, in order, with its steps' narration. */
    public List<ScenarioDto> scenarios() {
        return scenarios.stream().map(ScenarioEngine::toDto).toList();
    }

    /** The most recently started run, if any is kept. */
    public Optional<RunDto> latestRun() {
        synchronized (lock) {
            return Optional.ofNullable(history.peekLast()).map(ScenarioRun::toDto);
        }
    }

    /** @throws UnknownScenarioRunException if no kept run has this id */
    public RunDto run(long runId) {
        synchronized (lock) {
            return history.stream().filter(run -> run.runId() == runId).findFirst().map(ScenarioRun::toDto)
                    .orElseThrow(() -> new UnknownScenarioRunException(runId));
        }
    }

    // ------------------------------------------------------------------ actions

    /**
     * Starts a run in the background.
     *
     * @throws UnknownScenarioException no scenario has this id
     * @throws ScenarioBusyException    a run is in progress
     */
    public RunAcceptedDto start(String scenarioId) {
        Scenario scenario = scenarios.stream().filter(s -> s.id().equals(scenarioId)).findFirst()
                .orElseThrow(() -> new UnknownScenarioException(scenarioId));
        ScenarioRun run;
        synchronized (lock) {
            if (current != null && current.isRunning()) {
                throw new ScenarioBusyException(current.runId(), current.title());
            }
            run = new ScenarioRun(++lastRunId, scenario, clock.instant());
            current = run;
            history.addLast(run);
            while (history.size() > properties.historyLimit()) {
                history.removeFirst();
            }
        }
        try {
            runner.execute(() -> execute(run, scenario));
        } catch (RejectedExecutionException e) {
            run.finish(RunState.FAILED, "Failed: the scenario engine is shutting down.", endState(null),
                    clock.instant());
            release(run);
            throw e;
        }
        return new RunAcceptedDto(run.runId(), scenario.id());
    }

    /** Stops the runner thread (a running step is interrupted; cleanup still runs). Called by Spring. */
    @Override
    public void close() {
        runner.shutdownNow();
        try {
            if (!runner.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Scenario runner did not stop within {} s", CLOSE_WAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ the run (runner thread)

    private void execute(ScenarioRun run, Scenario scenario) {
        RunMemory memory = new RunMemory();
        RunState outcome = RunState.FAILED;
        String verdict = null;
        try {
            long resetSequence;
            try {
                resetSequence = cleanReset();
            } catch (StepFailedException e) {
                for (int i = 1; i <= run.stepCount(); i++) {
                    run.skip(i, "Not run: the cluster could not be reset first.");
                }
                verdict = "Failed before the first step: " + e.getMessage();
                return;
            }
            Map<String, Object> started = data(run);
            started.put("title", run.title());
            started.put("steps", run.stepCount());
            publish("SCENARIO_STARTED", "Scenario started: " + run.title(), started);

            List<ScenarioStep> steps = scenario.steps();
            int failedAt = 0;
            String failure = null;
            for (int i = 1; i <= steps.size(); i++) {
                if (failedAt > 0) {
                    run.skip(i, "Skipped: step " + failedAt + " failed.");
                    Map<String, Object> skipped = data(run);
                    skipped.put("stepIndex", i);
                    publish("STEP_SKIPPED", "Step " + i + " skipped: step " + failedAt + " failed", skipped);
                    continue;
                }
                String message = runStep(run, i, steps.get(i - 1), memory, resetSequence);
                if (message != null) {
                    failedAt = i;
                    failure = message;
                }
            }
            if (failedAt == 0) {
                outcome = RunState.PASSED;
                verdict = "Passed: all " + steps.size() + " steps met their expectations.";
            } else {
                verdict = "Failed at step " + failedAt + ": " + failure;
            }
        } catch (RuntimeException | Error e) {
            log.error("Scenario run {} ({}) failed in the engine", run.runId(), run.scenarioId(), e);
            verdict = "Failed: unexpected error in the scenario engine: " + describe(e);
        } finally {
            String note = cleanup(scenario, memory);
            String finalVerdict = verdict != null ? verdict : "Failed: the run ended unexpectedly.";
            EndStateDto end = endState(note);
            try {
                // published before the run reads as finished, so a client that sees the end also finds the event
                Map<String, Object> finished = data(run);
                finished.put("title", run.title());
                finished.put("outcome", outcome.name());
                finished.put("verdict", finalVerdict);
                publish("SCENARIO_FINISHED", finalVerdict, finished);
            } finally {
                run.finish(outcome, finalVerdict, end, clock.instant());
                release(run);
            }
        }
    }

    private void release(ScenarioRun run) {
        synchronized (lock) {
            if (current == run) {
                current = null;
            }
        }
    }

    /** @return null if the step passed, else its failure message */
    private String runStep(ScenarioRun run, int index, ScenarioStep step, RunMemory memory, long resetSequence) {
        long deadline = step.deadline() == null ? properties.stepDeadlineMillis() : step.deadline().toMillis();
        StepContext context = new StepContext(bus, memory, resetSequence, deadline, properties.pollIntervalMillis(),
                nanoClock);
        run.stepStarted(index, clock.instant());
        Map<String, Object> started = data(run);
        started.put("stepIndex", index);
        started.put("narration", step.narration());
        publish("STEP_STARTED", "Step " + index + " of " + run.stepCount() + ": " + step.narration(), started);

        StepState state = StepState.FAILED;
        String message;
        try {
            message = step.body().run(context);
            if (message == null || message.isBlank()) {
                message = "Expectation met.";
            }
            state = StepState.PASSED;
        } catch (StepFailedException e) {
            message = e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            message = "Stopped: the backend is shutting down.";
        } catch (Exception e) {
            log.warn("Scenario run {} step {} threw", run.runId(), index, e);
            message = "Unexpected error: " + describe(e);
        }
        long duration = context.elapsedMillis();
        Map<String, Object> facts = context.facts();
        run.stepEnded(index, state, duration, facts, message);
        Map<String, Object> ended = data(run);
        ended.put("stepIndex", index);
        ended.put("durationMs", duration);
        ended.put("facts", facts);
        boolean passed = state == StepState.PASSED;
        publish(passed ? "STEP_PASSED" : "STEP_FAILED",
                "Step " + index + (passed ? " passed: " : " failed: ") + message, ended);
        return passed ? null : message;
    }

    /**
     * Resets the cluster, retrying while a module is BUSY.
     *
     * @return the bus sequence of this reset's CLUSTER_RESET event
     * @throws StepFailedException if a module stayed busy until the reset timeout
     */
    private long cleanReset() {
        long start = nanoClock.getAsLong();
        long limit = TimeUnit.MILLISECONDS.toNanos(properties.resetTimeoutMillis());
        while (true) {
            try {
                reset.run();
                return bus.query(StepContext.CLUSTER_MODULE, 0, Integer.MAX_VALUE).stream()
                        .filter(e -> StepContext.CLUSTER_RESET.equals(e.type()))
                        .mapToLong(ClusterEvent::sequence).max().orElse(bus.publishedCount());
            } catch (ModuleBusyException e) {
                if (nanoClock.getAsLong() - start >= limit) {
                    throw new StepFailedException(String.format(Locale.ROOT,
                            "the cluster could not be reset, because module '%s' stayed busy for %.1f s.",
                            e.moduleId(), properties.resetTimeoutMillis() / 1000.0));
                }
                try {
                    Thread.sleep(properties.pollIntervalMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new StepFailedException("the backend is shutting down.");
                }
            }
        }
    }

    private String cleanup(Scenario scenario, RunMemory memory) {
        boolean interrupted = Thread.interrupted();   // let the cleanup's own waits work during a shutdown
        try {
            return scenario.cleanup(memory);
        } catch (RuntimeException e) {
            log.warn("Cleanup of scenario {} failed", scenario.id(), e);
            return "Cleanup failed: " + describe(e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The cluster as the run leaves it, read now. */
    EndStateDto endState(String note) {
        List<Integer> up = new ArrayList<>();
        List<Integer> down = new ArrayList<>();
        List<EndStateNodeDto> nodes = new ArrayList<>();
        for (ClusterNode node : cluster.nodes()) {
            (node.isUp() ? up : down).add(node.id());
            nodes.add(new EndStateNodeDto(node.id(), node.status().name(), node.roles().stream()
                    .sorted(Comparator.comparingInt(NodeRole::ordinal)).map(NodeRole::name).toList()));
        }
        Integer leader = cluster.leaderId().orElse(null);
        Integer primary = cluster.primaryId().orElse(null);
        StringBuilder text = new StringBuilder();
        if (down.isEmpty()) {
            text.append("All ").append(up.size()).append(" nodes are up.");
        } else if (up.isEmpty()) {
            text.append("Every node is down.");
        } else {
            text.append(Narration.sentence(Narration.nodes(up))).append(up.size() == 1 ? " is up; " : " are up; ")
                    .append(Narration.nodes(down)).append(down.size() == 1 ? " is down." : " are down.");
        }
        if (leader != null && leader.equals(primary)) {
            text.append(" Node ").append(leader).append(" is the leader and the replication primary.");
        } else {
            text.append(leader == null ? " No node is the leader." : " Node " + leader + " is the leader.");
            if (primary != null) {
                text.append(" Node ").append(primary).append(" is the replication primary.");
            }
        }
        if (note != null && !note.isBlank()) {
            text.append(' ').append(note);
        }
        return new EndStateDto(text.toString(), leader, primary, List.copyOf(nodes));
    }

    private Map<String, Object> data(ScenarioRun run) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", run.runId());
        data.put("scenarioId", run.scenarioId());
        return data;
    }

    private void publish(String type, String message, Map<String, Object> data) {
        bus.publish(EventDraft.of(MODULE, 0, type, cluster.clusterClock().tick()).withMessage(message).withData(data));
    }

    private static String describe(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static ScenarioDto toDto(Scenario scenario) {
        List<StepDefinitionDto> steps = new ArrayList<>();
        List<ScenarioStep> defined = scenario.steps();
        for (int i = 0; i < defined.size(); i++) {
            steps.add(new StepDefinitionDto(i + 1, defined.get(i).narration()));
        }
        return new ScenarioDto(scenario.id(), scenario.title(), scenario.summary(), List.copyOf(scenario.modules()),
                List.copyOf(steps));
    }
}
