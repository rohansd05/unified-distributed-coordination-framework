package com.udcf.scenario;

import com.udcf.modules.faulttolerance.FaultToleranceModule;
import com.udcf.scenario.dto.RunDto;
import com.udcf.scenario.dto.RunState;
import com.udcf.scenario.dto.StepDto;
import com.udcf.scenario.dto.StepState;
import com.udcf.scenario.scenarios.LeaderDiesMidWriteScenario;
import com.udcf.scenario.scenarios.SlowNodeUnderLoadScenario;
import com.udcf.scenario.scenarios.SplitBrainNeverHappensScenario;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Each of the three scenarios once, through the engine, on the real five-node cluster with real sockets
 * on 127.0.0.1 and the real (local) timings.
 *
 * <p>Ports (Track B block, below 32768): election 26661-26665, replication 26671-26675, requests
 * 26681-26685 bind; RMI 26690, clock 26260 and MapReduce 26270 are bases only (never started). The
 * context is closed after the class. A run is awaited for at most 4 minutes (the worst case of 7 steps
 * at the 30 s step deadline is 3.5 min); step deadlines are the configured 30 s, well above Experiment 8's
 * worst-case failover (about 10.3 s).</p>
 */
@SpringBootTest(properties = {
        "udcf.cluster.ports.rmi-base=26690", "udcf.cluster.ports.clock-base=26260",
        "udcf.cluster.ports.election-base=26660", "udcf.cluster.ports.replication-base=26670",
        "udcf.cluster.ports.requests-base=26680", "udcf.cluster.ports.mapreduce-base=26270"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScenariosEndToEndTest {

    private static final Duration RUN_DEADLINE = Duration.ofMinutes(4);

    @Autowired
    private ScenarioEngine engine;

    @Autowired
    private FaultToleranceModule faultTolerance;

    private RunDto runToTheEnd(String scenarioId) {
        long runId = engine.start(scenarioId).runId();
        RunDto run = await().atMost(RUN_DEADLINE).pollInterval(Duration.ofMillis(500))
                .until(() -> engine.run(runId), r -> r.state() != RunState.RUNNING);
        System.out.println("Scenario " + scenarioId + ": " + describe(run));   // the observed narration, in the CI log
        return run;
    }

    /** The whole run, for a failure message. */
    private static String describe(RunDto run) {
        return run.verdict() + "\n" + run.steps().stream()
                .map(s -> s.index() + " " + s.state() + ": " + s.message() + " " + s.facts())
                .collect(Collectors.joining("\n")) + "\n" + (run.endState() == null ? "" : run.endState().description());
    }

    private static Map<String, Object> facts(RunDto run, int index) {
        return run.steps().get(index - 1).facts();
    }

    private static long number(RunDto run, int index, String key) {
        Object value = facts(run, index).get(key);
        assertThat(value).as("fact %s of step %d", key, index).isInstanceOf(Number.class);
        return ((Number) value).longValue();
    }

    @Test
    @DisplayName("\"The leader dies mid-write\": crash of leader node 5, node 4 elected and promoted at a higher epoch after LEADER_CHANGED, 0 confirmed updates lost")
    void leaderDiesMidWrite() {
        RunDto run = runToTheEnd(LeaderDiesMidWriteScenario.ID);

        assertThat(run.state()).as(describe(run)).isEqualTo(RunState.PASSED);
        assertThat(run.steps()).extracting(StepDto::state).containsOnly(StepState.PASSED);
        assertThat(number(run, 1, "leaderId")).isEqualTo(5);   // Bully elects the highest live node
        assertThat(number(run, 1, "primaryId")).isEqualTo(5);
        assertThat(number(run, 2, "crashedNodeId")).isEqualTo(5);
        assertThat(number(run, 3, "silentMillis")).isPositive();
        assertThat(number(run, 4, "newLeaderId")).isEqualTo(4);
        assertThat(number(run, 5, "epoch")).isGreaterThan(number(run, 1, "epoch"));
        assertThat(number(run, 5, "promotedSequence")).isGreaterThan(number(run, 5, "leaderChangedSequence"));
        assertThat(number(run, 6, "lost")).isZero();
        assertThat(number(run, 6, "acknowledged")).isPositive();
        assertThat(run.steps().get(5).message()).contains("0 of ").contains("confirmed updates were lost");

        assertThat(run.endState().leaderId()).isEqualTo(4);
        assertThat(run.endState().primaryId()).isEqualTo(4);
        assertThat(run.endState().nodes().get(4).status()).isEqualTo("CRASHED");
        assertThat(run.endState().description()).startsWith("Nodes 1, 2, 3 and 4 are up; node 5 is down.")
                .contains("The update stream is stopped after");
        assertThat(faultTolerance.currentState().stream().state()).isEqualTo("STOPPED");
    }

    @Test
    @DisplayName("\"Split-brain that never happens\": node 5 recovers, demotes, resynchronises, is re-elected and takes over at a higher epoch; never two primaries")
    void splitBrainNeverHappens() {
        RunDto run = runToTheEnd(SplitBrainNeverHappensScenario.ID);

        assertThat(run.state()).as(describe(run)).isEqualTo(RunState.PASSED);
        assertThat(number(run, 2, "newLeaderId")).isEqualTo(4);
        long failoverEpoch = number(run, 2, "epoch");
        assertThat(number(run, 3, "recoveredNodeId")).isEqualTo(5);
        assertThat(number(run, 3, "epoch")).isEqualTo(failoverEpoch);
        assertThat(number(run, 3, "primaryId")).isEqualTo(4);
        assertThat(number(run, 4, "sourceNodeId")).isEqualTo(4);
        assertThat(number(run, 5, "leaderId")).isEqualTo(5);
        assertThat(number(run, 6, "fencedNodeId")).isEqualTo(4);
        assertThat(number(run, 6, "epoch")).isGreaterThan(failoverEpoch);
        assertThat(number(run, 6, "promotedSequence")).isGreaterThan(number(run, 6, "handoverStartedSequence"));
        assertThat(number(run, 6, "handoverStartedSequence")).isGreaterThan(number(run, 6, "leaderChangedSequence"));
        assertThat(number(run, 7, "samples")).isPositive();
        assertThat(number(run, 7, "maxLivePrimaries")).isEqualTo(1);
        assertThat(facts(run, 7).get("violations")).asInstanceOf(InstanceOfAssertFactories.LIST).isEmpty();
        assertThat(number(run, 7, "violationsSeenByExperiment8")).isZero();

        assertThat(run.endState().leaderId()).isEqualTo(5);
        assertThat(run.endState().primaryId()).isEqualTo(5);
        assertThat(run.endState().description()).startsWith("All 5 nodes are up. Node 5 is the leader and the "
                + "replication primary.");
    }

    @Test
    @DisplayName("\"A slow node under load\": node 3 is slow; the comparison finishes; the verdict is measured (PASSED, or FAILED only on the measured step), always with the four makespans and requests to node 3")
    void slowNodeUnderLoad() {
        RunDto run = runToTheEnd(SlowNodeUnderLoadScenario.ID);

        assertThat(run.steps().get(0).state()).as(describe(run)).isEqualTo(StepState.PASSED);
        assertThat(run.steps().get(1).state()).as(describe(run)).isEqualTo(StepState.PASSED);
        assertThat(number(run, 1, "slowNodeId")).isEqualTo(3);   // capacity pattern FAST, MEDIUM, SLOW, MEDIUM, FAST
        StepDto measured = run.steps().get(2);
        if (run.state() == RunState.PASSED) {
            assertThat(measured.state()).isEqualTo(StepState.PASSED);
            assertThat(measured.message()).startsWith("Round robin finished last after ");
        } else {
            // A measured result, not a fixed claim: on a busy machine round robin may not be last.
            assertThat(measured.state()).as(describe(run)).isEqualTo(StepState.FAILED);
            assertThat(measured.message()).startsWith("Round robin did not finish last this time");
        }
        assertThat(measured.facts().get("makespanMillis")).asInstanceOf(
                InstanceOfAssertFactories.map(String.class, Object.class))
                .containsOnlyKeys("ROUND_ROBIN", "WEIGHTED_ROUND_ROBIN", "LEAST_CONNECTIONS", "LEAST_RESPONSE_TIME")
                .allSatisfy((strategy, millis) -> assertThat(((Number) millis).doubleValue()).isPositive());
        assertThat(measured.facts().get("requestsToSlowNode")).asInstanceOf(
                InstanceOfAssertFactories.map(String.class, Object.class))
                .containsOnlyKeys("ROUND_ROBIN", "WEIGHTED_ROUND_ROBIN", "LEAST_CONNECTIONS", "LEAST_RESPONSE_TIME")
                .allSatisfy((strategy, count) -> assertThat(((Number) count).intValue()).isNotNegative());
        assertThat(run.endState().description()).isEqualTo("All 5 nodes are up. No node is the leader.");
    }
}
