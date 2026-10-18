package com.udcf.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udcf.scenario.scenarios.LeaderDiesMidWriteScenario;
import com.udcf.scenario.scenarios.SlowNodeUnderLoadScenario;
import com.udcf.scenario.scenarios.SplitBrainNeverHappensScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The scenario API with MockMvc in its own Spring context. Only a fake scenario is run (its step waits
 * on a latch), so no node service starts and nothing binds: the port bases are numbers only, the same
 * as ScenariosEndToEndTest's (Track B block, below 32768). The three real scenarios are listed, never run.
 */
@SpringBootTest(properties = {
        "udcf.cluster.ports.rmi-base=26690", "udcf.cluster.ports.clock-base=26260",
        "udcf.cluster.ports.election-base=26660", "udcf.cluster.ports.replication-base=26670",
        "udcf.cluster.ports.requests-base=26680", "udcf.cluster.ports.mapreduce-base=26270"})
@AutoConfigureMockMvc
@Import(ScenarioControllerTest.GateConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScenarioControllerTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    static final CountDownLatch GATE = new CountDownLatch(1);

    @TestConfiguration
    static class GateConfig {
        @Bean
        Scenario gateScenario() {
            return new Scenario() {
                @Override
                public String id() {
                    return "test-gate";
                }

                @Override
                public String title() {
                    return "Test gate";
                }

                @Override
                public String summary() {
                    return "Waits until the test opens the gate.";
                }

                @Override
                public List<String> modules() {
                    return List.of("test");
                }

                @Override
                public int order() {
                    return 99;
                }

                @Override
                public List<ScenarioStep> steps() {
                    return List.of(
                            ScenarioStep.of("We wait for the gate.", step -> {
                                step.fact("opened", GATE.await(15, TimeUnit.SECONDS));
                                return "The gate opened.";
                            }),
                            ScenarioStep.of("We are done.", step -> "Done."));
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode json(String path) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(path)).andReturn().getResponse().getContentAsString());
    }

    @Test
    @Order(1)
    @DisplayName("GET /runs/latest before any run is 204 with no body")
    void latestBeforeAnyRun() throws Exception {
        mockMvc.perform(get("/api/scenarios/runs/latest"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @Order(2)
    @DisplayName("GET /api/scenarios lists the three scenarios in order, with 1-based steps whose narration has no numbers")
    void listsScenarios() throws Exception {
        mockMvc.perform(get("/api/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(LeaderDiesMidWriteScenario.ID))
                .andExpect(jsonPath("$[0].title").value("The leader dies mid-write"))
                .andExpect(jsonPath("$[0].modules[2]").value("faulttolerance"))
                .andExpect(jsonPath("$[0].steps.length()").value(6))
                .andExpect(jsonPath("$[0].steps[0].index").value(1))
                .andExpect(jsonPath("$[1].id").value(SplitBrainNeverHappensScenario.ID))
                .andExpect(jsonPath("$[1].steps.length()").value(7))
                .andExpect(jsonPath("$[2].id").value(SlowNodeUnderLoadScenario.ID))
                .andExpect(jsonPath("$[2].steps.length()").value(3))
                .andExpect(jsonPath("$[3].id").value("test-gate"));

        for (JsonNode scenario : json("/api/scenarios")) {
            assertThat(scenario.fieldNames()).toIterable()
                    .containsExactly("id", "title", "summary", "modules", "steps");
            for (JsonNode step : scenario.get("steps")) {
                String narration = step.get("narration").asText();
                // lab names ("Experiment 8") are names, not values; no other number may appear
                assertThat(narration.replaceAll("Experiment \\d+", "Experiment"))
                        .as("narration of %s", scenario.get("id"))
                        .doesNotContainPattern("[0-9]").matches("[A-Z].*\\.");
            }
        }
    }

    @Test
    @Order(3)
    @DisplayName("POST an unknown scenario is 404 with scenarioId; an unknown run is 404 with runId; a non-numeric run id is 400")
    void notFound() throws Exception {
        mockMvc.perform(post("/api/scenarios/no-such-scenario/runs"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Unknown scenario"))
                .andExpect(jsonPath("$.scenarioId").value("no-such-scenario"))
                .andExpect(jsonPath("$.status").value(404));
        mockMvc.perform(get("/api/scenarios/runs/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Unknown scenario run"))
                .andExpect(jsonPath("$.runId").value(999));
        mockMvc.perform(get("/api/scenarios/runs/abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(4)
    @DisplayName("a run: 202, then 409 SCENARIO_BUSY with its runId while it runs, RUNNING with PENDING steps, then PASSED with the end state")
    void runLifecycle() throws Exception {
        String accepted = mockMvc.perform(post("/api/scenarios/test-gate/runs"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.scenarioId").value("test-gate"))
                .andReturn().getResponse().getContentAsString();
        long runId = objectMapper.readTree(accepted).get("runId").asLong();

        mockMvc.perform(post("/api/scenarios/test-gate/runs"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Scenario busy"))
                .andExpect(jsonPath("$.reason").value("SCENARIO_BUSY"))
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.detail", containsString("Test gate")));

        await().atMost(WAIT).until(() -> "RUNNING".equals(json("/api/scenarios/runs/" + runId)
                .at("/steps/0/state").asText()));
        mockMvc.perform(get("/api/scenarios/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.finishedAt").isEmpty())
                .andExpect(jsonPath("$.verdict").isEmpty())
                .andExpect(jsonPath("$.endState").isEmpty())
                .andExpect(jsonPath("$.steps[1].state").value("PENDING"))
                .andExpect(jsonPath("$.steps[1].message").isEmpty());

        GATE.countDown();
        await().atMost(WAIT).until(() -> "PASSED".equals(json("/api/scenarios/runs/" + runId).get("state").asText()));

        JsonNode run = json("/api/scenarios/runs/latest");
        assertThat(run.get("runId").asLong()).isEqualTo(runId);
        assertThat(run.fieldNames()).toIterable().containsExactly("runId", "scenarioId", "state", "startedAt",
                "finishedAt", "verdict", "endState", "steps");
        assertThat(run.get("verdict").asText()).isEqualTo("Passed: all 2 steps met their expectations.");
        assertThat(run.at("/steps/0").fieldNames()).toIterable().containsExactly("index", "narration", "state",
                "startedAt", "durationMs", "facts", "message");
        assertThat(run.at("/steps/0/facts/opened").asBoolean()).isTrue();
        assertThat(run.at("/steps/0/message").asText()).isEqualTo("The gate opened.");
        assertThat(run.at("/endState/description").asText()).startsWith("All 5 nodes are up.");
        assertThat(run.at("/endState/nodes")).hasSize(5);
        assertThat(run.at("/endState").fieldNames()).toIterable()
                .containsExactly("description", "leaderId", "primaryId", "nodes");
    }
}
