package com.udcf.scenario.scenarios;

import com.udcf.modules.matrix.MatrixModule;
import com.udcf.scenario.ScenarioEngine;
import com.udcf.scenario.dto.RunDto;
import com.udcf.scenario.dto.RunState;
import com.udcf.scenario.dto.StepDto;
import com.udcf.scenario.dto.StepState;
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
 * The two 9A.7 scenarios once each, through the engine, on the real five-node cluster with real sockets
 * on 127.0.0.1 and the real (local) timings.
 *
 * <p>Ports (Track C's 9A.7 block 29500-29899, below 32768; a search of the test sources found it unused):
 * RMI 29501-29505, election 29521-29525, requests 29541-29545 and MapReduce 29551-29555 bind; clock 29510
 * and replication 29530 are bases only. The context is closed after the class. A run is awaited for at
 * most 4 minutes; step deadlines are the configured 30 s (60 s for the matrix runs), well above a
 * re-election (about 2.5 to 4 s) and Experiment 8's worst-case failover (about 10.3 s).</p>
 */
@SpringBootTest(properties = {
        "udcf.cluster.ports.rmi-base=29500", "udcf.cluster.ports.clock-base=29510",
        "udcf.cluster.ports.election-base=29520", "udcf.cluster.ports.replication-base=29530",
        "udcf.cluster.ports.requests-base=29540", "udcf.cluster.ports.mapreduce-base=29550"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IncidentScenariosEndToEndTest {

    private static final Duration RUN_DEADLINE = Duration.ofMinutes(4);

    @Autowired
    private ScenarioEngine engine;

    @Autowired
    private MatrixModule matrix;

    private RunDto runToTheEnd(String scenarioId) {
        long runId = engine.start(scenarioId).runId();
        RunDto run = await().atMost(RUN_DEADLINE).pollInterval(Duration.ofMillis(500))
                .until(() -> engine.run(runId), r -> r.state() != RunState.RUNNING);
        System.out.println("Scenario " + scenarioId + ": " + describe(run));   // the observed narration, in the CI log
        return run;
    }

    private static String describe(RunDto run) {
        return run.verdict() + "\n" + run.steps().stream()
                .map(s -> s.index() + " " + s.state() + " (" + s.durationMs() + " ms): " + s.message() + " " + s.facts())
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
    @DisplayName("\"Analyse the incident with MapReduce\": leader 5 coordinates, crashes; node 4 is elected and coordinates both counts, which match the export and include the LEADER_CHANGED")
    void mapReduceIncident() {
        RunDto run = runToTheEnd(MapReduceIncidentScenario.ID);

        assertThat(run.state()).as(describe(run)).isEqualTo(RunState.PASSED);
        assertThat(run.steps()).extracting(StepDto::state).containsOnly(StepState.PASSED);
        assertThat(number(run, 1, "leaderId")).isEqualTo(5);   // Bully elects the highest live node
        assertThat(number(run, 1, "coordinatorId")).isEqualTo(5);
        assertThat(number(run, 2, "crashedNodeId")).isEqualTo(5);
        assertThat(number(run, 3, "newLeaderId")).isEqualTo(4);
        assertThat(number(run, 3, "coordinatorChangedSequence")).isGreaterThan(number(run, 3, "leaderChangedSequence"));
        assertThat(facts(run, 4).get("leaderChangedExported")).isEqualTo(true);
        long exported = number(run, 4, "exportedLines");
        assertThat(exported).isPositive();
        assertThat(number(run, 5, "coordinatorId")).isEqualTo(4);
        assertThat(number(run, 5, "leaderChangedCount")).isPositive();
        assertThat(number(run, 5, "totalCounted")).isEqualTo(exported);
        assertThat(number(run, 6, "coordinatorId")).isEqualTo(4);
        assertThat(number(run, 6, "totalCounted")).isEqualTo(exported);
        assertThat(facts(run, 7).get("topTypes")).asInstanceOf(InstanceOfAssertFactories.MAP).hasSize(3);
        assertThat(run.steps().get(6).message()).startsWith("Most frequent types: ");

        assertThat(run.endState().leaderId()).isEqualTo(4);
        assertThat(run.endState().description()).startsWith("Nodes 1, 2, 3 and 4 are up; node 5 is down.");
    }

    @Test
    @DisplayName("\"The leader dies during a matrix multiply\": root 5 crashes after the start; the first outcome is reported; node 4 is elected and the same multiply verifies from root 4")
    void matrixLeaderCrash() {
        RunDto run = runToTheEnd(MatrixLeaderCrashScenario.ID);

        assertThat(run.state()).as(describe(run)).isEqualTo(RunState.PASSED);
        assertThat(run.steps()).extracting(StepDto::state).containsOnly(StepState.PASSED);
        assertThat(number(run, 1, "rootNodeId")).isEqualTo(5);
        assertThat(facts(run, 1).get("rootSource")).isEqualTo("LEADER");
        assertThat(number(run, 1, "size")).isEqualTo(matrix.overview().limits().maxSize());
        assertThat(number(run, 2, "crashedNodeId")).isEqualTo(5);
        // The first outcome is measured, not fixed: whatever it was, it is reported.
        assertThat(facts(run, 3).get("outcome")).isNotNull();
        assertThat(run.steps().get(2).message()).startsWith("The first multiply ");
        assertThat(number(run, 4, "newLeaderId")).isEqualTo(4);
        assertThat(number(run, 5, "rootNodeId")).isEqualTo(4);
        assertThat(facts(run, 5).get("rootSource")).isEqualTo("LEADER");
        assertThat(facts(run, 5).get("passed")).isEqualTo(true);
        assertThat(facts(run, 5).get("skippedNodeIds")).asInstanceOf(InstanceOfAssertFactories.LIST).contains(5);

        assertThat(run.endState().leaderId()).isEqualTo(4);
        assertThat(run.endState().description()).startsWith("Nodes 1, 2, 3 and 4 are up; node 5 is down.");
    }
}
