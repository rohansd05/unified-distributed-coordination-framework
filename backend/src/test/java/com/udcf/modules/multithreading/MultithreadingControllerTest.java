package com.udcf.modules.multithreading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udcf.core.module.ModuleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
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
 * The /api/modules/multithreading contract the E2d page relies on: every DTO's exact JSON
 * field names and types (so a rename breaks a test), the error bodies, and the 202/400/404/409
 * mapping. Replaces the pre-E2c test of the retired /api/multithreading controller.
 *
 * <p>Crashes nodes, so it has its own context: test-only requests ports 43201 to 43205, queue
 * capacity 20, and a short IO backpressure demo.</p>
 */
@SpringBootTest(properties = {
        "udcf.cluster.ports.requests-base=43200",
        "udcf.multithreading.queue-capacity=20",
        "udcf.multithreading.backpressure.extra-requests=5",
        "udcf.multithreading.backpressure.workload=IO_SIMULATED",
        "udcf.multithreading.backpressure.payload-size=100"
})
@AutoConfigureMockMvc
class MultithreadingControllerTest {

    private static final String BASE = "/api/modules/multithreading";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MultithreadingModule module;

    private JsonNode json(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("NaN").doesNotContain("Infinity");
        return objectMapper.readTree(body);
    }

    private static void assertFields(JsonNode node, String... names) {
        Set<String> actual = new TreeSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).as("fields of %s", node).isEqualTo(new TreeSet<>(List.of(names)));
    }

    private static void assertType(JsonNode node, String field, Predicate<JsonNode> type) {
        assertThat(type.test(node.get(field))).as("type of %s in %s", field, node).isTrue();
    }

    private ResultActions postBatch(int nodeId, String body) throws Exception {
        return mockMvc.perform(post(BASE + "/nodes/" + nodeId + "/batches")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void awaitIdle() {
        await().atMost(15, TimeUnit.SECONDS).until(() -> module.status() == ModuleStatus.IDLE);
    }

    private static void assertProblem(JsonNode problem, int status, String title, String... extraFields) {
        List<String> fields = new ArrayList<>(List.of("type", "title", "status", "detail", "instance"));
        fields.addAll(List.of(extraFields));
        assertFields(problem, fields.toArray(String[]::new));
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.get("title").asText()).isEqualTo(title);
        assertType(problem, "detail", JsonNode::isTextual);
    }

    @Test
    @DisplayName("GET overview: exact fields and types of the overview, workloads and nodes; stats null, never NaN")
    void overviewContract() throws Exception {
        JsonNode overview = json(mockMvc.perform(get(BASE)).andExpect(status().isOk()));

        assertFields(overview, "status", "actionInProgress", "capacityNote", "workloads", "nodes");
        assertType(overview, "status", JsonNode::isTextual);
        assertType(overview, "capacityNote", JsonNode::isTextual);
        assertType(overview, "workloads", JsonNode::isArray);
        assertType(overview, "nodes", JsonNode::isArray);
        assertThat(overview.get("workloads")).hasSize(3);
        for (JsonNode workload : overview.get("workloads")) {
            assertFields(workload, "type", "description", "simulated", "simulatedReason");
            assertType(workload, "type", JsonNode::isTextual);
            assertType(workload, "description", JsonNode::isTextual);
            assertType(workload, "simulated", JsonNode::isBoolean);
        }
        assertThat(overview.get("workloads").get(0).get("type").asText()).isEqualTo("CPU_HASH");
        assertThat(overview.get("workloads").get(0).get("simulated").asBoolean()).isFalse();
        assertThat(overview.get("workloads").get(0).get("simulatedReason").isNull()).isTrue();
        assertThat(overview.get("workloads").get(1).get("simulatedReason").isTextual()).isTrue();

        assertThat(overview.get("nodes")).hasSize(5);
        JsonNode node = overview.get("nodes").get(4);
        assertFields(node, "nodeId", "nodeStatus", "capacity", "capacityConfigured", "threads", "workMultiplier",
                "port", "serviceRunning", "stats");
        assertType(node, "nodeId", JsonNode::isInt);
        assertType(node, "nodeStatus", JsonNode::isTextual);
        assertType(node, "capacity", JsonNode::isTextual);
        assertType(node, "capacityConfigured", JsonNode::isBoolean);
        assertType(node, "threads", JsonNode::isInt);
        assertType(node, "workMultiplier", JsonNode::isInt);
        assertType(node, "port", JsonNode::isInt);
        assertType(node, "serviceRunning", JsonNode::isBoolean);
        assertThat(node.get("port").asInt()).isEqualTo(43205);
        assertThat(node.get("stats").isNull()).as("node 5 has never been used").isTrue();
        assertThat(node.get("capacityConfigured").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("POST batch: 202 with the exact batch fields; then the requests list and the node stats contracts")
    void batchRequestsAndStatsContract() throws Exception {
        JsonNode batch = json(postBatch(1, "{\"count\":6,\"type\":\"CPU_HASH\",\"payloadSize\":5}")
                .andExpect(status().isAccepted()));

        assertFields(batch, "batchId", "nodeId", "kind", "workload", "payloadSize", "requested", "accepted",
                "rejected", "requestIds");
        assertType(batch, "batchId", JsonNode::isTextual);
        assertType(batch, "requestIds", JsonNode::isArray);
        assertThat(batch.get("kind").asText()).isEqualTo("BATCH");
        assertThat(batch.get("workload").asText()).isEqualTo("CPU_HASH");
        assertThat(batch.get("nodeId").asInt()).isEqualTo(1);
        assertThat(batch.get("requested").asInt()).isEqualTo(6);
        assertThat(batch.get("accepted").asInt()).isEqualTo(6);
        assertThat(batch.get("rejected").asInt()).isZero();
        assertThat(batch.get("requestIds")).hasSize(6);
        awaitIdle();

        JsonNode requests = json(mockMvc.perform(get(BASE + "/nodes/1/requests").param("limit", "4"))
                .andExpect(status().isOk()));
        assertThat(requests).hasSize(4);
        JsonNode request = requests.get(0);
        assertFields(request, "id", "nodeId", "type", "status", "threadName", "submittedAt", "queueWaitMillis",
                "processingMillis", "totalMillis", "resultSummary", "errorMessage");
        assertType(request, "id", JsonNode::isTextual);
        assertType(request, "submittedAt", JsonNode::isTextual);
        assertType(request, "totalMillis", JsonNode::isNumber);
        assertThat(request.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(request.get("threadName").asText()).startsWith("udcf-worker-n1-");

        JsonNode stats = json(mockMvc.perform(get(BASE))).get("nodes").get(0).get("stats");
        assertFields(stats, "nodeId", "corePoolSize", "maxPoolSize", "poolSize", "activeThreads", "largestPoolSize",
                "queuedRequests", "queueCapacity", "queueRemainingCapacity", "completedTasks", "totalTasks",
                "requestsPerSecond", "averageResponseTimeMillis", "p95ResponseTimeMillis", "sampleCount",
                "statusCounts");
        assertType(stats, "requestsPerSecond", JsonNode::isNumber);
        assertType(stats, "completedTasks", JsonNode::isNumber);
        assertThat(stats.get("queueCapacity").asInt()).isEqualTo(20);
        assertFields(stats.get("statusCounts"), "QUEUED", "PROCESSING", "COMPLETED", "FAILED", "REJECTED");
    }

    @Test
    @DisplayName("invalid batch bodies are 400 with an errors map naming each field")
    void invalidBatchIs400() throws Exception {
        JsonNode countZero = json(postBatch(1, "{\"count\":0,\"type\":\"CPU_HASH\",\"payloadSize\":5}")
                .andExpect(status().isBadRequest()));
        assertProblem(countZero, 400, "Invalid request parameters", "errors");
        assertFields(countZero.get("errors"), "count");
        assertThat(countZero.get("errors").get("count").asText()).isEqualTo("count must be at least 1");

        JsonNode tooMany = json(postBatch(1, "{\"count\":1001,\"type\":null,\"payloadSize\":5001}")
                .andExpect(status().isBadRequest()));
        assertFields(tooMany.get("errors"), "count", "type", "payloadSize");
    }

    @Test
    @DisplayName("an unknown node is 404 with nodeId")
    void unknownNodeIs404() throws Exception {
        JsonNode problem = json(postBatch(9, "{\"count\":1,\"type\":\"CPU_HASH\",\"payloadSize\":5}")
                .andExpect(status().isNotFound()));

        assertProblem(problem, 404, "Unknown node", "nodeId");
        assertThat(problem.get("nodeId").asInt()).isEqualTo(9);
        mockMvc.perform(get(BASE + "/nodes/9/requests")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a crashed node is 409 Node down, and its history is still readable")
    void crashedNodeIs409() throws Exception {
        mockMvc.perform(post("/api/cluster/nodes/2/crash")).andExpect(status().isOk());
        try {
            JsonNode problem = json(postBatch(2, "{\"count\":1,\"type\":\"CPU_HASH\",\"payloadSize\":5}")
                    .andExpect(status().isConflict()));

            assertProblem(problem, 409, "Node down", "nodeId");
            assertThat(problem.get("nodeId").asInt()).isEqualTo(2);
            mockMvc.perform(get(BASE + "/nodes/2/requests")).andExpect(status().isOk());
            JsonNode node = json(mockMvc.perform(get(BASE))).get("nodes").get(1);
            assertThat(node.get("nodeStatus").asText()).isEqualTo("CRASHED");
            assertThat(node.get("stats").isNull()).isTrue();
        } finally {
            mockMvc.perform(post("/api/cluster/nodes/2/recover"));
        }
    }

    @Test
    @DisplayName("backpressure is 202 with rejections; a second demo is 409 Module busy; a crash releases it")
    void backpressureAndBusy() throws Exception {
        JsonNode demo = json(mockMvc.perform(post(BASE + "/nodes/3/backpressure")).andExpect(status().isAccepted()));
        try {
            assertThat(demo.get("kind").asText()).isEqualTo("BACKPRESSURE");
            assertThat(demo.get("requested").asInt()).isEqualTo(26);   // SLOW: 1 thread + 20 queue + 5
            assertThat(demo.get("rejected").asInt()).isEqualTo(5);

            JsonNode busy = json(mockMvc.perform(post(BASE + "/nodes/1/backpressure")).andExpect(status().isConflict()));
            assertProblem(busy, 409, "Module busy", "moduleId", "actionInProgress");
            assertThat(busy.get("moduleId").asText()).isEqualTo("multithreading");
            assertThat(busy.get("actionInProgress").asText()).isEqualTo("Backpressure demo on node 3");
            JsonNode overview = json(mockMvc.perform(get(BASE)));
            assertThat(overview.get("status").asText()).isEqualTo("BUSY");
            assertThat(overview.get("actionInProgress").asText()).isEqualTo("Backpressure demo on node 3");
        } finally {
            mockMvc.perform(post("/api/cluster/nodes/3/crash"));
            mockMvc.perform(post("/api/cluster/nodes/3/recover"));
        }
        awaitIdle();
        mockMvc.perform(post(BASE + "/nodes/1/backpressure")).andExpect(status().isAccepted());
        awaitIdle();
    }

    @Test
    @DisplayName("a requests limit outside 1 to the history size is 400 naming limit")
    void requestsLimitIsValidated() throws Exception {
        JsonNode zero = json(mockMvc.perform(get(BASE + "/nodes/1/requests").param("limit", "0"))
                .andExpect(status().isBadRequest()));
        assertFields(zero.get("errors"), "limit");
        JsonNode tooMany = json(mockMvc.perform(get(BASE + "/nodes/1/requests").param("limit", "501"))
                .andExpect(status().isBadRequest()));
        assertThat(tooMany.get("errors").get("limit").asText()).isEqualTo("must be less than or equal to 500");
        mockMvc.perform(get(BASE + "/nodes/4/requests")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the module is listed for the sidebar, and the retired /api/multithreading API is gone")
    void listedAndLegacyRetired() throws Exception {
        JsonNode modules = json(mockMvc.perform(get("/api/modules")).andExpect(status().isOk()));
        assertThat(modules.findValuesAsText("id")).contains("multithreading");

        mockMvc.perform(get("/api/multithreading/stats")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/multithreading/requests/sync")).andExpect(status().isNotFound());
    }
}
