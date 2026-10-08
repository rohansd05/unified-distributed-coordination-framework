package com.udcf.modules.loadbalancing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udcf.core.cluster.Cluster;
import com.udcf.core.module.ModuleStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The /api/modules/loadbalancing contract the E6d page relies on: every DTO's exact JSON field
 * names and types (a rename breaks a test), no NaN anywhere, the error bodies, the 202, 400,
 * 404 and 409 mapping, and the meters as Prometheus scrapes them.
 *
 * <p>Crashes nodes, so it has its own context: test-only requests ports 21881 to 21885, below
 * 32768 and outside the Linux and Windows ephemeral ranges. Each test starts with the module
 * idle and reset, and every crashed node recovered.</p>
 */
@SpringBootTest(properties = "udcf.cluster.ports.requests-base=21880")
@AutoConfigureMockMvc
@AutoConfigureObservability
class LoadBalancingControllerTest {

    private static final String BASE = "/api/modules/loadbalancing";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoadBalancingModule module;

    @Autowired
    private Cluster cluster;

    @BeforeEach
    void cleanStart() {
        awaitIdle();
        cluster.recoverAll();
        module.reset();
    }

    @AfterEach
    void cleanEnd() {
        awaitIdle();
        cluster.recoverAll();
    }

    private void awaitIdle() {
        await().atMost(60, TimeUnit.SECONDS).until(() -> module.status() == ModuleStatus.IDLE);
    }

    private JsonNode json(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("NaN").doesNotContain("Infinity");
        return objectMapper.readTree(body);
    }

    private JsonNode overview() throws Exception {
        return json(mockMvc.perform(get(BASE)).andExpect(status().isOk()));
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(BASE + path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static void assertFields(JsonNode node, String... names) {
        Set<String> actual = new TreeSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).as("fields of %s", node).isEqualTo(new TreeSet<>(List.of(names)));
    }

    private static void assertType(JsonNode node, String field, Predicate<JsonNode> type) {
        assertThat(type.test(node.get(field))).as("type of %s in %s", field, node).isTrue();
    }

    private static void assertProblem(JsonNode problem, int status, String title, String... extraFields) {
        List<String> fields = new ArrayList<>(List.of("type", "title", "status", "detail", "instance"));
        fields.addAll(List.of(extraFields));
        assertFields(problem, fields.toArray(String[]::new));
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.get("title").asText()).isEqualTo(title);
        assertType(problem, "detail", JsonNode::isTextual);
    }

    private static final String[] RUN_FIELDS = {"runId", "state", "strategy", "requestCount", "workUnits",
            "concurrency", "crash", "startedAt", "finishedAt", "report", "error"};
    private static final String[] REPORT_FIELDS = {"runId", "strategy", "requestCount", "served", "failures",
            "reroutes", "makespanMillis", "averageLatencyMillis", "p95LatencyMillis", "maxLatencyMillis",
            "loadSpread", "nodes"};
    private static final String[] NODE_RESULT_FIELDS = {"nodeId", "capacity", "requests", "averageLatencyMillis",
            "failedAttempts", "declinedAttempts", "healthy"};

    private static void assertReport(JsonNode report) {
        assertFields(report, REPORT_FIELDS);
        assertType(report, "runId", JsonNode::isTextual);
        assertType(report, "strategy", JsonNode::isTextual);
        for (String f : List.of("requestCount", "served", "failures", "reroutes", "loadSpread")) {
            assertType(report, f, JsonNode::isIntegralNumber);
        }
        for (String f : List.of("makespanMillis", "averageLatencyMillis", "p95LatencyMillis", "maxLatencyMillis")) {
            assertType(report, f, JsonNode::isNumber);
        }
        assertThat(report.get("nodes")).hasSize(5);
        JsonNode node = report.get("nodes").get(0);
        assertFields(node, NODE_RESULT_FIELDS);
        assertType(node, "capacity", JsonNode::isTextual);
        assertType(node, "requests", JsonNode::isIntegralNumber);
        assertType(node, "healthy", JsonNode::isBoolean);
        assertType(node, "averageLatencyMillis", n -> n.isNumber() || n.isNull());
    }

    @Test
    @DisplayName("GET: the overview's exact fields, settings, texts and five idle workers, with no results yet")
    void overviewContract() throws Exception {
        JsonNode body = overview();

        assertFields(body, "status", "actionInProgress", "defaults", "limits", "capacityNote", "workUnitsNote",
                "deliveryNote", "warmUpNote", "crashNote", "strategies", "workers", "latestRun", "latestComparison");
        assertThat(body.get("status").asText()).isEqualTo("IDLE");
        assertThat(body.get("actionInProgress").isNull()).isTrue();
        assertThat(body.get("latestRun").isNull()).isTrue();
        assertThat(body.get("latestComparison").isNull()).isTrue();
        assertFields(body.get("defaults"), "requestCount", "workUnits", "concurrency");
        assertFields(body.get("limits"), "maxRequestCount", "maxWorkUnits", "maxConcurrency", "maxTotalWork");
        assertThat(body.get("limits").get("maxTotalWork").asLong()).isEqualTo(500_000);
        for (String note : List.of("capacityNote", "workUnitsNote", "deliveryNote", "warmUpNote", "crashNote")) {
            assertType(body, note, JsonNode::isTextual);
        }
        assertThat(body.get("strategies")).hasSize(4);
        JsonNode strategy = body.get("strategies").get(0);
        assertFields(strategy, "strategy", "description", "informationUsed");
        assertThat(strategy.get("strategy").asText()).isEqualTo("ROUND_ROBIN");

        assertThat(body.get("workers")).hasSize(5);
        JsonNode worker = body.get("workers").get(0);
        assertFields(worker, "nodeId", "nodeStatus", "capacity", "threads", "workMultiplier", "weight", "port",
                "healthy", "inFlight", "completed", "failed", "declined", "ewmaLatencyMillis", "averageLatencyMillis");
        assertThat(worker.get("nodeStatus").asText()).isEqualTo("UP");
        assertThat(worker.get("capacity").asText()).isEqualTo("FAST");
        assertThat(worker.get("port").asInt()).isEqualTo(21881);
        assertThat(worker.get("ewmaLatencyMillis").isNull()).isTrue();
        assertThat(worker.get("averageLatencyMillis").isNull()).isTrue();
    }

    @Test
    @DisplayName("POST /runs: 202 RUNNING with no report, then the overview shows it FINISHED with the measured report")
    void runContract() throws Exception {
        JsonNode accepted = json(postJson("/runs",
                "{\"strategy\":\"ROUND_ROBIN\",\"requestCount\":20,\"workUnits\":5,\"concurrency\":4}")
                .andExpect(status().isAccepted()));

        assertFields(accepted, RUN_FIELDS);
        assertThat(accepted.get("state").asText()).isEqualTo("RUNNING");
        assertThat(accepted.get("report").isNull()).isTrue();
        assertThat(accepted.get("crash").isNull()).isTrue();
        assertThat(accepted.get("finishedAt").isNull()).isTrue();
        assertType(accepted, "startedAt", JsonNode::isTextual);
        awaitIdle();

        JsonNode run = overview().get("latestRun");
        assertFields(run, RUN_FIELDS);
        assertThat(run.get("state").asText()).isEqualTo("FINISHED");
        assertThat(run.get("runId").asText()).isEqualTo(accepted.get("runId").asText());
        assertThat(run.get("error").isNull()).isTrue();
        assertReport(run.get("report"));
        assertThat(run.get("report").get("failures").asInt()).isZero();
        assertThat(run.get("report").get("nodes")).allSatisfy(n -> assertThat(n.get("requests").asInt()).isEqualTo(4));
    }

    @Test
    @DisplayName("POST /runs with a crash plan: the node is crashed by the module, every request is still served")
    void crashRunContract() throws Exception {
        JsonNode accepted = json(postJson("/runs", "{\"strategy\":\"ROUND_ROBIN\",\"requestCount\":30,"
                + "\"workUnits\":5,\"concurrency\":4,\"crash\":{\"nodeId\":3,\"afterServed\":10}}")
                .andExpect(status().isAccepted()));
        assertFields(accepted.get("crash"), "nodeId", "afterServed", "crashed");
        assertThat(accepted.get("crash").get("crashed").asBoolean()).isFalse();
        awaitIdle();

        JsonNode body = overview();
        JsonNode run = body.get("latestRun");
        assertThat(run.get("crash").get("crashed").asBoolean()).isTrue();
        assertThat(run.get("report").get("failures").asInt()).isZero();
        assertThat(run.get("report").get("served").asInt()).isEqualTo(30);
        JsonNode node3 = body.get("workers").get(2);
        assertThat(node3.get("nodeStatus").asText()).isEqualTo("CRASHED");
        assertThat(node3.get("healthy").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("POST /comparisons: 202 RUNNING, then FINISHED with four phases and a finding of measured facts")
    void comparisonContract() throws Exception {
        String[] fields = {"comparisonId", "state", "requestCount", "workUnits", "concurrency", "warmUpRequests",
                "startedAt", "finishedAt", "phases", "finding", "error"};
        JsonNode accepted = json(postJson("/comparisons", "{\"requestCount\":20,\"workUnits\":5,\"concurrency\":4}")
                .andExpect(status().isAccepted()));
        assertFields(accepted, fields);
        assertThat(accepted.get("state").asText()).isEqualTo("RUNNING");
        assertThat(accepted.get("warmUpRequests").asInt()).isEqualTo(20);
        assertThat(accepted.get("phases")).isEmpty();
        assertThat(accepted.get("finding").isNull()).isTrue();
        awaitIdle();

        JsonNode done = overview().get("latestComparison");
        assertFields(done, fields);
        assertThat(done.get("state").asText()).isEqualTo("FINISHED");
        assertThat(done.get("phases")).hasSize(4);
        done.get("phases").forEach(LoadBalancingControllerTest::assertReport);
        JsonNode finding = done.get("finding");
        assertFields(finding, "fastest", "slowest", "mostEven", "roundRobinFinishedLast", "roundRobinMostEven",
                "gainOverRoundRobinPercent");
        assertType(finding, "roundRobinFinishedLast", JsonNode::isBoolean);
        assertType(finding, "roundRobinMostEven", JsonNode::isBoolean);
        assertType(finding, "fastest", JsonNode::isTextual);
        assertType(finding, "gainOverRoundRobinPercent", n -> n.isNumber() || n.isNull());
    }

    @Test
    @DisplayName("400 with an errors entry: missing strategy, count out of range, total-work cap, bad crash point; and an unknown strategy name")
    void validation() throws Exception {
        record Case(String path, String body, String field) {
        }
        List<Case> cases = List.of(
                new Case("/runs", "{\"requestCount\":20,\"workUnits\":5,\"concurrency\":4}", "strategy"),
                new Case("/runs", "{\"strategy\":\"ROUND_ROBIN\",\"requestCount\":0,\"workUnits\":5,\"concurrency\":4}",
                        "requestCount"),
                new Case("/runs", "{\"strategy\":\"ROUND_ROBIN\",\"requestCount\":1000,\"workUnits\":501,\"concurrency\":4}",
                        "totalWork"),
                new Case("/runs", "{\"strategy\":\"ROUND_ROBIN\",\"requestCount\":20,\"workUnits\":5,\"concurrency\":4,"
                        + "\"crash\":{\"nodeId\":3,\"afterServed\":20}}", "crash.afterServed"),
                new Case("/comparisons", "{\"requestCount\":300,\"workUnits\":400,\"concurrency\":4}", "totalWork"));
        for (Case c : cases) {
            JsonNode problem = json(postJson(c.path(), c.body()).andExpect(status().isBadRequest()));
            assertProblem(problem, 400, "Invalid request parameters", "errors");
            assertThat(problem.get("errors").has(c.field())).as(c.body()).isTrue();
        }
        postJson("/runs", "{\"strategy\":\"FASTEST\",\"requestCount\":20,\"workUnits\":5,\"concurrency\":4}")
                .andExpect(status().isBadRequest());
        assertThat(overview().get("latestRun").isNull()).isTrue();
    }

    @Test
    @DisplayName("404 Unknown node when the crash plan names a node that does not exist")
    void unknownCrashNode() throws Exception {
        JsonNode problem = json(postJson("/runs", "{\"strategy\":\"ROUND_ROBIN\",\"requestCount\":20,"
                + "\"workUnits\":5,\"concurrency\":4,\"crash\":{\"nodeId\":9,\"afterServed\":5}}")
                .andExpect(status().isNotFound()));

        assertProblem(problem, 404, "Unknown node", "nodeId");
        assertThat(problem.get("nodeId").asInt()).isEqualTo(9);
    }

    @Test
    @DisplayName("409 Node down when the crash plan names a node that is already crashed")
    void crashNodeDown() throws Exception {
        mockMvc.perform(post("/api/cluster/nodes/2/crash")).andExpect(status().is2xxSuccessful());

        JsonNode problem = json(postJson("/runs", "{\"strategy\":\"ROUND_ROBIN\",\"requestCount\":20,"
                + "\"workUnits\":5,\"concurrency\":4,\"crash\":{\"nodeId\":2,\"afterServed\":5}}")
                .andExpect(status().isConflict()));

        assertProblem(problem, 409, "Node down", "nodeId");
        assertThat(problem.get("nodeId").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("409 Module busy while a run is executing; the overview says BUSY and what is running")
    void busy() throws Exception {
        // A deliberately long run (200 requests of 2000 units, one client: seconds, not milliseconds).
        postJson("/runs", "{\"strategy\":\"ROUND_ROBIN\",\"requestCount\":200,\"workUnits\":2000,\"concurrency\":1}")
                .andExpect(status().isAccepted());

        JsonNode problem = json(postJson("/comparisons", "{\"requestCount\":6,\"workUnits\":5,\"concurrency\":2}")
                .andExpect(status().isConflict()));
        JsonNode body = overview();

        assertProblem(problem, 409, "Module busy", "moduleId", "actionInProgress");
        assertThat(problem.get("moduleId").asText()).isEqualTo("loadbalancing");
        assertThat(body.get("status").asText()).isEqualTo("BUSY");
        assertThat(body.get("actionInProgress").asText()).isEqualTo("Load balancing run with round robin, 200 requests");
    }

    @Test
    @DisplayName("Prometheus: dispatch counters per worker node_id, makespan at node_id 0, in-flight 0 for each of 5 nodes")
    void prometheus() throws Exception {
        postJson("/runs", "{\"strategy\":\"LEAST_CONNECTIONS\",\"requestCount\":20,\"workUnits\":5,\"concurrency\":4}")
                .andExpect(status().isAccepted());
        awaitIdle();

        String scrape = mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> lines = Arrays.stream(scrape.split("\n")).filter(l -> !l.startsWith("#")).toList();

        // Meters accumulate across this class's tests (one context), so check shape, not totals.
        assertThat(lines).filteredOn(l -> l.startsWith("distributed_balancer_dispatches_total{")).isNotEmpty()
                .allMatch(l -> l.contains("node_id=\"") && l.contains("strategy=\"") && l.contains("outcome=\""))
                .anyMatch(l -> l.contains("strategy=\"LEAST_CONNECTIONS\"") && l.contains("outcome=\"served\""));
        assertThat(lines).filteredOn(l -> l.startsWith("distributed_balancer_makespan_seconds_count{")).isNotEmpty()
                .allMatch(l -> l.contains("node_id=\"0\""))
                .anyMatch(l -> l.contains("strategy=\"LEAST_CONNECTIONS\""));
        assertThat(lines).filteredOn(l -> l.startsWith("distributed_balancer_in_flight{")).hasSize(5)
                .allMatch(l -> l.endsWith(" 0.0"));
    }
}
