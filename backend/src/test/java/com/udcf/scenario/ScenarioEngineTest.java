package com.udcf.scenario;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventDraft;
import com.udcf.core.events.EventProperties;
import com.udcf.core.module.ModuleBusyException;
import com.udcf.scenario.dto.RunDto;
import com.udcf.scenario.dto.RunState;
import com.udcf.scenario.dto.StepDto;
import com.udcf.scenario.dto.StepState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The scenario engine without sockets: a real bus and a three-node {@link Cluster} whose services never
 * start (the port bases are numbers only; nothing binds), and a fake reset that publishes CLUSTER_RESET as
 * the real one does.
 */
class ScenarioEngineTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final ClusterProperties CLUSTER = new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(26660, 26670, 26680, 26690, 26260, 26270));

    private ClusterEventBus bus;
    private Cluster cluster;
    private final AtomicInteger resets = new AtomicInteger();
    private final AtomicInteger busyResetsLeft = new AtomicInteger();
    private final List<ScenarioEngine> engines = new ArrayList<>();

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(5000, 1024), Clock.systemUTC());
        cluster = new Cluster(CLUSTER, bus);
    }

    @AfterEach
    void tearDown() {
        engines.forEach(ScenarioEngine::close);
        cluster.close();
        bus.close();
    }

    private void reset() {
        if (busyResetsLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new ModuleBusyException("election", "a long-running action");
        }
        resets.incrementAndGet();
        bus.publish(EventDraft.of("cluster", 0, "CLUSTER_RESET", cluster.clusterClock().tick()));
    }

    private ScenarioEngine engine(ScenarioProperties properties, Scenario... scenarios) {
        ScenarioEngine engine = new ScenarioEngine(List.of(scenarios), cluster, bus, this::reset, properties,
                Clock.systemUTC(), System::nanoTime);
        engines.add(engine);
        return engine;
    }

    private ScenarioEngine engine(Scenario... scenarios) {
        return engine(properties(10, 5000, 50), scenarios);
    }

    private static ScenarioProperties properties(int history, long stepDeadline, long poll) {
        return new ScenarioProperties(history, 1000, stepDeadline, poll, new ScenarioProperties.SlowNode(1));
    }

    private RunDto finished(ScenarioEngine engine, long runId) {
        return await().atMost(WAIT).until(() -> engine.run(runId), run -> run.state() != RunState.RUNNING);
    }

    private List<String> scenarioEventTypes() {
        return bus.query(null, null, Integer.MAX_VALUE).stream()
                .sorted(Comparator.comparingLong(ClusterEvent::sequence))
                .filter(e -> e.module().equals(ScenarioEngine.MODULE) || e.type().equals("CLUSTER_RESET"))
                .map(ClusterEvent::type).toList();
    }

    private void publishMarker(String type) {
        bus.publish(EventDraft.of("test", 1, type, 1));
    }

    @Test
    @DisplayName("a passing run: clean reset first, every step PASSED with its facts, events in order, end state read from the cluster")
    void passingRun() {
        ScenarioEngine engine = engine(new FakeScenario("ok", 1,
                ScenarioStep.of("First we look.", step -> {
                    step.fact("seen", 3);
                    return "Saw 3.";
                }),
                ScenarioStep.of("Then we look again.", step -> "Saw it again.")));

        long runId = engine.start("ok").runId();
        RunDto run = finished(engine, runId);

        assertThat(run.state()).isEqualTo(RunState.PASSED);
        assertThat(run.verdict()).isEqualTo("Passed: all 2 steps met their expectations.");
        assertThat(run.steps()).extracting(StepDto::state).containsExactly(StepState.PASSED, StepState.PASSED);
        assertThat(run.steps()).extracting(StepDto::index).containsExactly(1, 2);
        assertThat(run.steps().get(0).facts()).containsEntry("seen", 3);
        assertThat(run.steps().get(0).message()).isEqualTo("Saw 3.");
        assertThat(run.steps().get(0).durationMs()).isNotNull();
        assertThat(run.endState().description()).isEqualTo("All 3 nodes are up. No node is the leader.");
        assertThat(run.endState().nodes()).hasSize(3);
        assertThat(resets).hasValue(1);
        assertThat(scenarioEventTypes()).containsExactly("CLUSTER_RESET", "SCENARIO_STARTED", "STEP_STARTED",
                "STEP_PASSED", "STEP_STARTED", "STEP_PASSED", "SCENARIO_FINISHED");
        ClusterEvent finishedEvent = bus.query(ScenarioEngine.MODULE, 0, 1).get(0);
        assertThat(finishedEvent.data()).containsEntry("runId", runId).containsEntry("outcome", "PASSED")
                .containsEntry("title", "Fake ok");
    }

    @Test
    @DisplayName("R2: a failed step fails the run, skips the rest, and the cleanup still runs and is in the end state")
    void failedStepSkipsTheRestAndCleanupRuns() {
        AtomicBoolean thirdRan = new AtomicBoolean();
        FakeScenario scenario = new FakeScenario("fails", 1,
                ScenarioStep.of("One.", step -> "Fine."),
                ScenarioStep.of("Two.", step -> {
                    step.fact("lost", 2);
                    throw step.fail("Node 4 lacks 2 of 9 confirmed updates.");
                }),
                ScenarioStep.of("Three.", step -> {
                    thirdRan.set(true);
                    return "Never.";
                }));
        scenario.cleanupNote = "The update stream is stopped.";
        ScenarioEngine engine = engine(scenario);

        RunDto run = finished(engine, engine.start("fails").runId());

        assertThat(run.state()).isEqualTo(RunState.FAILED);
        assertThat(run.verdict()).isEqualTo("Failed at step 2: Node 4 lacks 2 of 9 confirmed updates.");
        assertThat(run.steps()).extracting(StepDto::state)
                .containsExactly(StepState.PASSED, StepState.FAILED, StepState.SKIPPED);
        assertThat(run.steps().get(1).facts()).containsEntry("lost", 2);
        assertThat(run.steps().get(2).message()).isEqualTo("Skipped: step 2 failed.");
        assertThat(run.steps().get(2).startedAt()).isNull();
        assertThat(thirdRan).isFalse();
        assertThat(scenario.cleanups).hasValue(1);
        assertThat(run.endState().description()).endsWith("The update stream is stopped.");
        assertThat(scenarioEventTypes()).contains("STEP_FAILED", "STEP_SKIPPED").endsWith("SCENARIO_FINISHED");
    }

    @Test
    @DisplayName("R2: an unexpected exception makes the step FAILED with its message; the run never stays RUNNING")
    void unexpectedExceptionFailsTheStep() {
        FakeScenario scenario = new FakeScenario("throws", 1,
                ScenarioStep.of("One.", step -> {
                    throw new IllegalStateException("boom");
                }),
                ScenarioStep.of("Two.", step -> "Never."));
        ScenarioEngine engine = engine(scenario);

        RunDto run = finished(engine, engine.start("throws").runId());

        assertThat(run.state()).isEqualTo(RunState.FAILED);
        assertThat(run.steps().get(0).message()).isEqualTo("Unexpected error: boom");
        assertThat(run.steps().get(1).state()).isEqualTo(StepState.SKIPPED);
        assertThat(scenario.cleanups).hasValue(1);
    }

    @Test
    @DisplayName("a cleanup that throws is reported in the end state, and the run still ends")
    void failingCleanupIsReported() {
        FakeScenario scenario = new FakeScenario("bad-cleanup", 1, ScenarioStep.of("One.", step -> "Fine."));
        scenario.cleanupError = new IllegalStateException("stream stuck");
        ScenarioEngine engine = engine(scenario);

        RunDto run = finished(engine, engine.start("bad-cleanup").runId());

        assertThat(run.state()).isEqualTo(RunState.PASSED);
        assertThat(run.endState().description()).endsWith("Cleanup failed: stream stuck");
    }

    @Test
    @DisplayName("a wait that outlives the step's deadline fails the step with what it waited for")
    void deadlineFailsTheStep() {
        ScenarioEngine engine = engine(new FakeScenario("slow", 1, new ScenarioStep("Wait.", Duration.ofMillis(300),
                step -> step.awaitEvent(step.mark(), "test", "NEVER", e -> true, "the marker").type())));

        RunDto run = finished(engine, engine.start("slow").runId());

        assertThat(run.state()).isEqualTo(RunState.FAILED);
        assertThat(run.steps().get(0).message()).matches("Waited \\d+\\.\\d s for the marker, but it did not happen\\.");
        // StepContext truncates nanoseconds to whole milliseconds, so a 300 ms deadline may read as 299 ms.
        assertThat(run.steps().get(0).durationMs()).isGreaterThanOrEqualTo(300 - 5);
    }

    @Test
    @DisplayName("R1: a wait from an anchor accepts an event published after the anchor but before the waiting step started")
    void anchorAcceptsAnEventPublishedBeforeTheWaitingStep() {
        AtomicLong stepTwoStart = new AtomicLong();
        ScenarioEngine engine = engine(new FakeScenario("anchor", 1,
                ScenarioStep.of("Act.", step -> {
                    step.anchor("act");
                    publishMarker("CONSEQUENCE");   // the consequence arrives while step 1 is still running
                    return "Acted.";
                }),
                ScenarioStep.of("Observe.", step -> {
                    stepTwoStart.set(step.mark());
                    ClusterEvent found = step.awaitEvent(step.anchorOf("act"), "test", "CONSEQUENCE", e -> true,
                            "the consequence");
                    step.fact("sequence", found.sequence());
                    return "Found it.";
                })));

        RunDto run = finished(engine, engine.start("anchor").runId());

        assertThat(run.state()).isEqualTo(RunState.PASSED);
        assertThat((Long) run.steps().get(1).facts().get("sequence")).isLessThan(stepTwoStart.get());
    }

    @Test
    @DisplayName("requireOrder fails a step when the later event came first, and records both sequences")
    void requireOrderChecksBusSequence() {
        ScenarioEngine engine = engine(new FakeScenario("order", 1, ScenarioStep.of("Order.", step -> {
            long anchor = step.mark();
            publishMarker("FIRST");
            publishMarker("SECOND");
            ClusterEvent first = step.awaitEvent(anchor, "test", "FIRST", e -> true, "first");
            ClusterEvent second = step.awaitEvent(anchor, "test", "SECOND", e -> true, "second");
            step.requireOrder("second", second, "first", first);
            return "Never.";
        })));

        RunDto run = finished(engine, engine.start("order").runId());

        StepDto step = run.steps().get(0);
        assertThat(step.state()).isEqualTo(StepState.FAILED);
        assertThat(step.message()).contains("FIRST").contains("came before SECOND");
        assertThat(step.facts()).containsKeys("secondSequence", "firstSequence");
    }

    @Test
    @DisplayName("the bus subscription wakes a wait long before the polling interval")
    void subscriptionWakesTheWait() {
        ScenarioEngine engine = engine(properties(10, 20_000, 10_000), new FakeScenario("wake", 1,
                ScenarioStep.of("Wait.", step -> {
                    long anchor = step.mark();
                    Thread.ofVirtual().start(() -> {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            return;
                        }
                        publishMarker("LATER");
                    });
                    step.awaitEvent(anchor, "test", "LATER", e -> true, "the later event");
                    return "Woken.";
                })));

        RunDto run = finished(engine, engine.start("wake").runId());

        assertThat(run.state()).isEqualTo(RunState.PASSED);
        assertThat(run.steps().get(0).durationMs()).isLessThan(5_000);   // the poll interval is 10 s
    }

    @Test
    @DisplayName("the polling fallback finds a matching event the subscription never delivered (published before the wait)")
    void pollingFindsAnEarlierEvent() {
        ScenarioEngine engine = engine(new FakeScenario("poll", 1, ScenarioStep.of("Look.", step -> {
            long anchor = step.mark();
            publishMarker("ALREADY");
            await().atMost(WAIT).until(() -> bus.query("test", null, 10).size() == 1);
            return step.awaitEvent(anchor, "test", "ALREADY", e -> true, "the event").type();
        })));

        assertThat(finished(engine, engine.start("poll").runId()).state()).isEqualTo(RunState.PASSED);
    }

    @Test
    @DisplayName("a cluster reset by someone else fails the waiting step")
    void foreignResetFailsTheWaitingStep() {
        ScenarioEngine engine = engine(new FakeScenario("foreign", 1, ScenarioStep.of("Wait.", step -> {
            long anchor = step.mark();
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
                bus.publish(EventDraft.of("cluster", 0, "CLUSTER_RESET", 1));
            });
            return step.awaitEvent(anchor, "test", "NEVER", e -> true, "nothing").type();
        })));

        RunDto run = finished(engine, engine.start("foreign").runId());

        assertThat(run.steps().get(0).state()).isEqualTo(StepState.FAILED);
        assertThat(run.steps().get(0).message()).startsWith("The cluster was reset during the scenario (event ");
    }

    @Test
    @DisplayName("one run at a time: a second start answers busy with the running run's id; afterwards a new run starts")
    void oneRunAtATime() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        ScenarioEngine engine = engine(
                new FakeScenario("blocks", 1, ScenarioStep.of("Block.", step -> {
                    release.await(10, TimeUnit.SECONDS);
                    return "Released.";
                })),
                new FakeScenario("other", 2, ScenarioStep.of("Go.", step -> "Gone.")));

        long first = engine.start("blocks").runId();
        assertThatThrownBy(() -> engine.start("other")).isInstanceOf(ScenarioBusyException.class)
                .satisfies(e -> assertThat(((ScenarioBusyException) e).runId()).isEqualTo(first));
        assertThat(engine.latestRun()).hasValueSatisfying(run -> assertThat(run.state()).isEqualTo(RunState.RUNNING));

        release.countDown();
        finished(engine, first);
        long second = engine.start("other").runId();
        assertThat(second).isEqualTo(first + 1);
        assertThat(finished(engine, second).state()).isEqualTo(RunState.PASSED);
    }

    @Test
    @DisplayName("only the last history-limit runs are kept; an evicted or unknown run id is unknown")
    void historyIsBounded() {
        ScenarioEngine engine = engine(properties(2, 5000, 50), new FakeScenario("quick", 1,
                ScenarioStep.of("Go.", step -> "Gone.")));

        for (int i = 0; i < 3; i++) {
            finished(engine, engine.start("quick").runId());
        }

        assertThat(engine.latestRun()).hasValueSatisfying(run -> assertThat(run.runId()).isEqualTo(3));
        assertThat(engine.run(2).runId()).isEqualTo(2);
        assertThatThrownBy(() -> engine.run(1)).isInstanceOf(UnknownScenarioRunException.class);
        assertThatThrownBy(() -> engine.run(99)).isInstanceOf(UnknownScenarioRunException.class);
    }

    @Test
    @DisplayName("the clean reset is retried while a module is busy")
    void resetRetriedWhileBusy() {
        busyResetsLeft.set(2);
        ScenarioEngine engine = engine(new FakeScenario("retry", 1, ScenarioStep.of("Go.", step -> "Gone.")));

        RunDto run = finished(engine, engine.start("retry").runId());

        assertThat(run.state()).isEqualTo(RunState.PASSED);
        assertThat(resets).hasValue(1);
    }

    @Test
    @DisplayName("a module busy past the reset timeout fails the run before the first step; every step is SKIPPED")
    void resetTimeoutFailsTheRun() {
        busyResetsLeft.set(Integer.MAX_VALUE);
        FakeScenario scenario = new FakeScenario("never-reset", 1, ScenarioStep.of("Go.", step -> "Gone."),
                ScenarioStep.of("Again.", step -> "Gone."));
        ScenarioEngine engine = engine(scenario);

        RunDto run = finished(engine, engine.start("never-reset").runId());

        assertThat(run.state()).isEqualTo(RunState.FAILED);
        assertThat(run.verdict()).isEqualTo("Failed before the first step: the cluster could not be reset, "
                + "because module 'election' stayed busy for 1.0 s.");
        assertThat(run.steps()).extracting(StepDto::state).containsOnly(StepState.SKIPPED);
        assertThat(scenarioEventTypes()).containsExactly("SCENARIO_FINISHED");
        assertThat(scenario.cleanups).hasValue(1);
    }

    @Test
    @DisplayName("scenarios are listed in order with 1-based step narration; unknown and duplicate ids are refused")
    void listingAndValidation() {
        ScenarioEngine engine = engine(new FakeScenario("b", 20, ScenarioStep.of("Bee.", step -> "")),
                new FakeScenario("a", 10, ScenarioStep.of("Ay.", step -> ""), ScenarioStep.of("Ay two.", step -> "")));

        assertThat(engine.scenarios()).extracting(s -> s.id()).containsExactly("a", "b");
        assertThat(engine.scenarios().get(0).steps()).extracting(s -> s.index() + " " + s.narration())
                .containsExactly("1 Ay.", "2 Ay two.");
        assertThat(engine.latestRun()).isEmpty();
        assertThatThrownBy(() -> engine.start("missing")).isInstanceOf(UnknownScenarioException.class);
        assertThatThrownBy(() -> engine(new FakeScenario("x", 1, ScenarioStep.of("X.", step -> "")),
                new FakeScenario("x", 2, ScenarioStep.of("X.", step -> ""))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate scenario id 'x'");
    }

    @Test
    @DisplayName("a blank message from a passing step reads 'Expectation met.'")
    void blankMessage() {
        ScenarioEngine engine = engine(new FakeScenario("blank", 1, ScenarioStep.of("Go.", step -> "")));

        assertThat(finished(engine, engine.start("blank").runId()).steps().get(0).message())
                .isEqualTo("Expectation met.");
    }

    @Test
    @DisplayName("the end state names down nodes, the leader and the primary from the cluster")
    void endStateFromTheCluster() {
        ScenarioEngine engine = engine(new FakeScenario("end", 1, ScenarioStep.of("Go.", step -> "")));
        cluster.crash(3);
        cluster.assignLeader(2);
        cluster.assignReplicationRoles(1, List.of(2));

        var end = engine.endState("Done.");

        assertThat(end.description()).isEqualTo(
                "Nodes 1 and 2 are up; node 3 is down. Node 2 is the leader. Node 1 is the replication primary. Done.");
        assertThat(end.leaderId()).isEqualTo(2);
        assertThat(end.primaryId()).isEqualTo(1);
        assertThat(end.nodes().get(1).roles()).containsExactly("LEADER", "BACKUP");
        assertThat(end.nodes().get(2).status()).isEqualTo("CRASHED");
        cluster.assignLeader(1);
        assertThat(engine.endState(null).description())
                .endsWith("Node 1 is the leader and the replication primary.");
    }

    /** A scenario made of the given steps; it counts its cleanups. */
    private static final class FakeScenario implements Scenario {
        private final String id;
        private final int order;
        private final List<ScenarioStep> steps;
        private final AtomicInteger cleanups = new AtomicInteger();
        private volatile String cleanupNote;
        private volatile RuntimeException cleanupError;

        FakeScenario(String id, int order, ScenarioStep... steps) {
            this.id = id;
            this.order = order;
            this.steps = List.of(steps);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String title() {
            return "Fake " + id;
        }

        @Override
        public String summary() {
            return "A fake scenario.";
        }

        @Override
        public List<String> modules() {
            return List.of("test");
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public List<ScenarioStep> steps() {
            return steps;
        }

        @Override
        public String cleanup(RunMemory memory) {
            cleanups.incrementAndGet();
            if (cleanupError != null) {
                throw cleanupError;
            }
            return cleanupNote;
        }
    }
}
