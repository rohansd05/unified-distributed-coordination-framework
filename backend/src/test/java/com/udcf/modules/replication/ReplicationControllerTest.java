package com.udcf.modules.replication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udcf.core.cluster.Cluster;
import com.udcf.core.module.ModuleActionGuard;
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
 * The /api/modules/replication contract the E5d page relies on: every DTO's exact JSON field
 * names and types (a rename breaks a test), nulls instead of invented zeros, no NaN, the
 * ProblemDetail bodies for 400, 404 and 409, the clean-slate cluster reset, and the meters as
 * Prometheus scrapes them.
 *
 * <p>Crashes nodes, so it has its own context. {@code udcf.cluster.ports.replication-base=28700}
 * binds {@code ClusterProperties.Ports.replicationBase}, and node k listens on 28700 + k: ports
 * 28701 to 28705 under the local profile's five nodes, inside the Exp 5 test range 28100 to 28899.
 * Each test starts with the module idle and reset and every crashed node recovered.</p>
 */
@SpringBootTest(properties = "udcf.cluster.ports.replication-base=28700")
@AutoConfigureMockMvc
@AutoConfigureObservability
class ReplicationControllerTest {

    private static final String BASE = "/api/modules/replication";

    private static final String[] OVERVIEW_FIELDS = {"status", "actionInProgress", "primaryNodeId",
            "currentPrimaryNodeId", "takeoverPending", "lastTakeover", "asyncDelayMillis", "asyncDelayReason",
            "timeoutMillis", "batchSize", "conflictRuleNote", "models", "nodes", "healthMeasuredByNodeId", "health",
            "latestWrite", "latestAntiEntropy", "latestInjection"};
    private static final String[] NODE_FIELDS = {"nodeId", "nodeStatus", "role", "actingPrimary", "serviceRunning",
            "port", "epoch", "itemCount"};
    private static final String[] MODEL_FIELDS = {"model", "description", "guarantee", "simulated", "simulatedReason"};
    private static final String[] HEALTH_FIELDS = {"backupNodeId", "acks", "applied", "duplicates", "staleRejections",
            "staleEpochRejections", "failures", "averageLatencyMillis", "maxLatencyMillis", "lastSync"};
    private static final String[] WRITE_FIELDS = {"writeId", "model", "primaryNodeId", "item", "localResult",
            "confirmMillis", "simulated", "simulatedDelayMillis", "simulatedReason", "replicationState",
            "backupNodeIds", "pushes", "takeover"};
    private static final String[] ITEM_FIELDS = {"key", "value", "lamportTime", "originNode", "epoch"};
    private static final String[] PUSH_FIELDS = {"backupNodeId", "status", "result", "backupEpoch", "latencyMillis",
            "detail"};
    private static final String[] TAKEOVER_FIELDS = {"previousPrimaryNodeId", "newPrimaryNodeId", "appliedFromCatchUp",
            "catchUps", "pushes", "lamportTime"};
    private static final String[] CATCH_UP_FIELDS = {"sourceNodeId", "completed", "pulled", "applied", "alreadyCurrent",
            "stale", "staleEpoch", "sourceEpoch", "latencyMillis", "failure"};
    private static final String[] ANTI_ENTROPY_FIELDS = {"sourceNodeId", "targetNodeId", "completed", "pushed",
            "applied", "alreadyCurrent", "stale", "staleEpoch", "chunksPlanned", "chunksAcknowledged", "latencyMillis",
            "failure"};
    private static final String[] READ_FIELDS = {"nodeId", "key", "referenceNodeId", "reachable", "item",
            "referenceItem", "state", "error"};
    private static final String[] REPLICAS_FIELDS = {"referenceNodeId", "consistent", "divergences", "replicas", "rows"};
    private static final String[] COLUMN_FIELDS = {"nodeId", "nodeStatus", "reference", "reachable", "epoch",
            "itemCount", "error"};
    private static final String[] INJECTION_FIELDS = {"primaryNodeId", "backupNodeId", "key", "currentItem",
            "staleItem", "push", "rejected"};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReplicationModule module;

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
        await().atMost(30, TimeUnit.SECONDS).until(() -> module.status() == ModuleStatus.IDLE);
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

    private JsonNode write(String key, String model) throws Exception {
        return json(postJson("/writes", "{\"key\":\"" + key + "\",\"value\":\"v\",\"model\":\"" + model + "\"}")
                .andExpect(status().isOk()));
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

    private static void assertItem(JsonNode item) {
        assertFields(item, ITEM_FIELDS);
        assertType(item, "key", JsonNode::isTextual);
        assertType(item, "value", JsonNode::isTextual);
        assertType(item, "lamportTime", JsonNode::isIntegralNumber);
        assertType(item, "originNode", JsonNode::isIntegralNumber);
        assertType(item, "epoch", JsonNode::isIntegralNumber);
    }

    private static void assertAntiEntropy(JsonNode report) {
        assertFields(report, ANTI_ENTROPY_FIELDS);
        for (String f : List.of("sourceNodeId", "targetNodeId", "pushed", "applied", "alreadyCurrent", "stale",
                "staleEpoch", "chunksPlanned", "chunksAcknowledged")) {
            assertType(report, f, JsonNode::isIntegralNumber);
        }
        assertType(report, "completed", JsonNode::isBoolean);
        assertType(report, "latencyMillis", JsonNode::isNumber);
    }

    // ------------------------------------------------------------------ contracts

    @Test
    @DisplayName("GET overview after a reset: every field, five nodes, nulls for what is unknown, labelled simulated delay")
    void overviewContract() throws Exception {
        JsonNode body = overview();

        assertFields(body, OVERVIEW_FIELDS);
        assertThat(body.get("status").asText()).isEqualTo("IDLE");
        assertThat(body.get("primaryNodeId").asInt()).isEqualTo(1);
        for (String f : List.of("actionInProgress", "currentPrimaryNodeId", "lastTakeover", "healthMeasuredByNodeId",
                "latestWrite", "latestAntiEntropy", "latestInjection")) {
            assertThat(body.get(f).isNull()).as(f).isTrue();
        }
        assertThat(body.get("takeoverPending").asBoolean()).isFalse();
        assertThat(body.get("asyncDelayMillis").asLong()).isEqualTo(450);
        assertType(body, "asyncDelayReason", JsonNode::isTextual);
        assertThat(body.get("timeoutMillis").asInt()).isEqualTo(1500);
        assertThat(body.get("batchSize").asInt()).isEqualTo(200);
        assertType(body, "conflictRuleNote", JsonNode::isTextual);
        assertThat(body.get("health")).isEmpty();
        assertThat(body.get("models")).hasSize(2);
        assertFields(body.get("models").get(1), MODEL_FIELDS);
        assertThat(body.get("models").get(1).get("simulated").asBoolean()).isTrue();
        assertThat(body.get("nodes")).hasSize(5);
        JsonNode node = body.get("nodes").get(0);
        assertFields(node, NODE_FIELDS);
        assertThat(node.get("role").asText()).isEqualTo("PRIMARY");
        assertThat(node.get("port").asInt()).isEqualTo(28701);
    }

    @Test
    @DisplayName("POST /writes SYNCHRONOUS: 200, COMPLETE, four ACKED pushes, the first selection as takeover; the overview fills the health table")
    void syncWriteContract() throws Exception {
        JsonNode write = write("k;~", "SYNCHRONOUS");

        assertFields(write, WRITE_FIELDS);
        assertItem(write.get("item"));
        assertThat(write.get("item").get("key").asText()).isEqualTo("k;~");
        assertThat(write.get("replicationState").asText()).isEqualTo("COMPLETE");
        assertThat(write.get("simulated").asBoolean()).isFalse();
        assertThat(write.get("simulatedReason").isNull()).isTrue();
        assertType(write, "confirmMillis", JsonNode::isNumber);
        assertThat(write.get("pushes")).hasSize(4);
        JsonNode push = write.get("pushes").get(0);
        assertFields(push, PUSH_FIELDS);
        assertThat(push.get("status").asText()).isEqualTo("ACKED");
        assertThat(push.get("result").asText()).isEqualTo("APPLIED");
        assertType(push, "latencyMillis", JsonNode::isNumber);
        assertThat(push.get("detail").isNull()).isTrue();
        JsonNode takeover = write.get("takeover");
        assertFields(takeover, TAKEOVER_FIELDS);
        assertThat(takeover.get("previousPrimaryNodeId").isNull()).isTrue();
        assertThat(takeover.get("catchUps")).isEmpty();

        JsonNode body = overview();
        assertThat(body.get("currentPrimaryNodeId").asInt()).isEqualTo(1);
        assertThat(body.get("health")).hasSize(4);
        assertFields(body.get("health").get(0), HEALTH_FIELDS);
        assertType(body.get("health").get(0), "lastSync", JsonNode::isTextual);
        assertThat(body.get("latestWrite").get("writeId").asText()).isEqualTo(write.get("writeId").asText());
    }

    @Test
    @DisplayName("POST /writes ASYNCHRONOUS: 200, PENDING, simulated 450 ms with a reason; the overview shows it COMPLETE later")
    void asyncWriteContract() throws Exception {
        JsonNode write = write("k", "ASYNCHRONOUS");

        assertThat(write.get("replicationState").asText()).isEqualTo("PENDING");
        assertThat(write.get("pushes")).isEmpty();
        assertThat(write.get("simulated").asBoolean()).isTrue();
        assertThat(write.get("simulatedDelayMillis").asLong()).isEqualTo(450);
        assertType(write, "simulatedReason", JsonNode::isTextual);

        awaitIdle();
        JsonNode latest = overview().get("latestWrite");
        assertThat(latest.get("replicationState").asText()).isEqualTo("COMPLETE");
        assertThat(latest.get("pushes")).hasSize(4);
    }

    @Test
    @DisplayName("GET /nodes/{id}/values and /replicas: field names; an unreachable replica has null item, epoch and itemCount")
    void readAndReplicasContract() throws Exception {
        write("k", "SYNCHRONOUS");
        postJson("/nodes/5/crash", "").andExpect(status().isOk());

        JsonNode current = json(mockMvc.perform(get(BASE + "/nodes/2/values").param("key", "k")).andExpect(status().isOk()));
        JsonNode down = json(mockMvc.perform(get(BASE + "/nodes/5/values").param("key", "k")).andExpect(status().isOk()));
        JsonNode view = json(mockMvc.perform(get(BASE + "/replicas")).andExpect(status().isOk()));

        assertFields(current, READ_FIELDS);
        assertThat(current.get("state").asText()).isEqualTo("CURRENT");
        assertItem(current.get("item"));
        assertThat(current.get("error").isNull()).isTrue();
        assertThat(down.get("state").asText()).isEqualTo("UNREACHABLE");
        assertThat(down.get("item").isNull()).isTrue();
        assertType(down, "error", JsonNode::isTextual);
        assertFields(view, REPLICAS_FIELDS);
        assertThat(view.get("consistent").asBoolean()).isTrue();
        assertThat(view.get("replicas")).hasSize(5);
        JsonNode column = view.get("replicas").get(4);
        assertFields(column, COLUMN_FIELDS);
        assertThat(column.get("reachable").asBoolean()).isFalse();
        assertThat(column.get("epoch").isNull()).isTrue();
        assertThat(column.get("itemCount").isNull()).isTrue();
        JsonNode row = view.get("rows").get(0);
        assertFields(row, "key", "cells");
        assertFields(row.get("cells").get(0), "nodeId", "item", "state");
        assertThat(row.get("cells").get(4).get("state").asText()).isEqualTo("UNREACHABLE");
    }

    @Test
    @DisplayName("crash, recover, anti-entropy and stale injection: 200 with their field names")
    void actionsContract() throws Exception {
        write("k", "SYNCHRONOUS");

        JsonNode crashed = json(postJson("/nodes/3/crash", "").andExpect(status().isOk()));
        write("k2", "SYNCHRONOUS");
        JsonNode recovered = json(postJson("/nodes/3/recover", "").andExpect(status().isOk()));
        JsonNode antiEntropy = json(postJson("/anti-entropy", "{\"targetNodeId\":3}").andExpect(status().isOk()));
        JsonNode injection = json(postJson("/stale-injections",
                "{\"backupNodeId\":2,\"key\":\"k\",\"staleValue\":\"old\"}").andExpect(status().isOk()));

        assertFields(crashed, NODE_FIELDS);
        assertThat(crashed.get("nodeStatus").asText()).isEqualTo("CRASHED");
        assertThat(crashed.get("itemCount").isNull()).isTrue();
        assertThat(recovered.get("nodeStatus").asText()).isEqualTo("UP");
        assertAntiEntropy(antiEntropy);
        assertThat(antiEntropy.get("applied").asInt()).isEqualTo(1);
        assertThat(antiEntropy.get("failure").isNull()).isTrue();
        assertFields(injection, INJECTION_FIELDS);
        assertItem(injection.get("staleItem"));
        assertThat(injection.get("rejected").asBoolean()).isTrue();
        assertThat(injection.get("push").get("result").asText()).isEqualTo("STALE");
    }

    @Test
    @DisplayName("a takeover through the API: node 1 crashed from the cluster API makes it pending; the next write reports catch-up and pushes")
    void takeoverContract() throws Exception {
        write("k", "SYNCHRONOUS");
        mockMvc.perform(post("/api/cluster/nodes/1/crash")).andExpect(status().isOk());

        JsonNode pending = overview();
        assertThat(pending.get("takeoverPending").asBoolean()).isTrue();
        assertThat(pending.get("primaryNodeId").asInt()).isEqualTo(2);
        assertThat(pending.get("currentPrimaryNodeId").asInt()).isEqualTo(1);

        JsonNode write = write("k", "SYNCHRONOUS");
        JsonNode takeover = write.get("takeover");
        assertThat(takeover.get("previousPrimaryNodeId").asInt()).isEqualTo(1);
        assertThat(takeover.get("catchUps")).hasSize(3);
        assertFields(takeover.get("catchUps").get(0), CATCH_UP_FIELDS);
        assertThat(takeover.get("pushes")).hasSize(3);
        assertAntiEntropy(takeover.get("pushes").get(0));
        assertThat(overview().get("takeoverPending").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ errors

    @Test
    @DisplayName("400: invalid body fields, a control character in the key, the primary as a crash target, a key the primary lacks")
    void validationErrors() throws Exception {
        JsonNode blank = json(postJson("/writes", "{\"key\":\"\",\"value\":\"v\",\"model\":\"SYNCHRONOUS\"}")
                .andExpect(status().isBadRequest()));
        JsonNode noModel = json(postJson("/writes", "{\"key\":\"k\",\"value\":\"v\"}").andExpect(status().isBadRequest()));
        JsonNode control = json(postJson("/writes", "{\"key\":\"a\\u0007b\",\"value\":\"v\",\"model\":\"SYNCHRONOUS\"}")
                .andExpect(status().isBadRequest()));
        JsonNode primary = json(postJson("/nodes/1/crash", "").andExpect(status().isBadRequest()));
        write("k", "SYNCHRONOUS");
        JsonNode missing = json(postJson("/stale-injections", "{\"backupNodeId\":2,\"key\":\"nope\",\"staleValue\":\"x\"}")
                .andExpect(status().isBadRequest()));

        assertProblem(blank, 400, "Invalid request parameters", "errors");
        assertThat(blank.get("errors").has("key")).isTrue();
        assertThat(noModel.get("errors").has("model")).isTrue();
        assertProblem(control, 400, "Invalid request parameters", "errors");
        assertThat(control.get("errors").has("key")).isTrue();
        assertThat(primary.get("errors").has("nodeId")).isTrue();
        assertThat(primary.get("errors").get("nodeId").asText()).contains("Cluster page");
        assertThat(missing.get("errors").has("key")).isTrue();
        mockMvc.perform(get(BASE + "/nodes/2/values")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("404 Unknown node for a read, a crash and an anti-entropy target that do not exist")
    void unknownNode() throws Exception {
        JsonNode read = json(mockMvc.perform(get(BASE + "/nodes/9/values").param("key", "k"))
                .andExpect(status().isNotFound()));
        postJson("/nodes/9/crash", "").andExpect(status().isNotFound());
        postJson("/anti-entropy", "{\"targetNodeId\":9}").andExpect(status().isNotFound());

        assertProblem(read, 404, "Unknown node", "nodeId");
        assertThat(read.get("nodeId").asInt()).isEqualTo(9);
    }

    @Test
    @DisplayName("409: anti-entropy to a crashed node (Node down), recover of an up node (Node state conflict), any action while busy")
    void conflicts() throws Exception {
        write("k", "SYNCHRONOUS");
        postJson("/nodes/3/crash", "").andExpect(status().isOk());

        JsonNode down = json(postJson("/anti-entropy", "{\"targetNodeId\":3}").andExpect(status().isConflict()));
        JsonNode upAlready = json(postJson("/nodes/2/recover", "").andExpect(status().isConflict()));
        JsonNode busy;
        try (ModuleActionGuard.ActionTicket held = module.guard().begin("A test action")) {
            busy = json(postJson("/writes", "{\"key\":\"k\",\"value\":\"v\",\"model\":\"SYNCHRONOUS\"}")
                    .andExpect(status().isConflict()));
            assertThat(overview().get("status").asText()).isEqualTo("BUSY");
        }

        assertProblem(down, 409, "Node down", "nodeId");
        assertThat(down.get("nodeId").asInt()).isEqualTo(3);
        assertProblem(upAlready, 409, "Node state conflict", "nodeId");
        assertProblem(busy, 409, "Module busy", "moduleId", "actionInProgress");
        assertThat(busy.get("moduleId").asText()).isEqualTo("replication");
        assertThat(busy.get("actionInProgress").asText()).isEqualTo("A test action");
    }

    // ------------------------------------------------------------------ reset and metrics

    @Test
    @DisplayName("POST /api/cluster/reset is a clean slate for this module: stores, roles, stats, epochs, history and its events")
    void clusterResetIsCleanSlate() throws Exception {
        write("k", "SYNCHRONOUS");
        postJson("/stale-injections", "{\"backupNodeId\":2,\"key\":\"k\",\"staleValue\":\"old\"}").andExpect(status().isOk());
        postJson("/nodes/4/crash", "").andExpect(status().isOk());

        mockMvc.perform(post("/api/cluster/reset")).andExpect(status().isNoContent());

        JsonNode body = overview();
        for (String f : List.of("currentPrimaryNodeId", "lastTakeover", "latestWrite", "latestInjection",
                "healthMeasuredByNodeId")) {
            assertThat(body.get(f).isNull()).as(f).isTrue();
        }
        assertThat(body.get("health")).isEmpty();
        for (JsonNode node : body.get("nodes")) {
            assertThat(node.get("nodeStatus").asText()).isEqualTo("UP");
            assertThat(node.get("actingPrimary").asBoolean()).isFalse();
            assertThat(node.get("epoch").asLong()).isEqualTo(1);
            assertThat(node.get("itemCount").asInt()).isZero();
        }
        JsonNode events = json(mockMvc.perform(get("/api/events").param("module", "replication").param("limit", "50"))
                .andExpect(status().isOk()));
        assertThat(events).isEmpty();
        JsonNode view = json(mockMvc.perform(get(BASE + "/replicas")).andExpect(status().isOk()));
        assertThat(view.get("rows")).isEmpty();
    }

    @Test
    @DisplayName("a cluster reset is refused with 409 while the module is busy, and changes nothing")
    void clusterResetRefusedWhileBusy() throws Exception {
        write("k", "SYNCHRONOUS");
        try (ModuleActionGuard.ActionTicket held = module.guard().begin("A test action")) {
            mockMvc.perform(post("/api/cluster/reset")).andExpect(status().isConflict());
        }
        assertThat(overview().get("latestWrite").isNull()).isFalse();
    }

    @Test
    @DisplayName("Prometheus: replication meters with node_id; store-item and epoch gauges for each of 5 nodes")
    void prometheus() throws Exception {
        write("k", "SYNCHRONOUS");

        String scrape = mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> lines = Arrays.stream(scrape.split("\n")).filter(l -> !l.startsWith("#")).toList();

        // Meters accumulate across this class's tests (one context), so check shape, not totals.
        assertThat(lines).filteredOn(l -> l.startsWith("distributed_replication_")).isNotEmpty()
                .allMatch(l -> l.contains("node_id=\""));
        assertThat(lines).filteredOn(l -> l.startsWith("distributed_replication_acks_total{"))
                .anyMatch(l -> l.contains("result=\"APPLIED\""));
        assertThat(lines).filteredOn(l -> l.startsWith("distributed_replication_writes_total{"))
                .anyMatch(l -> l.contains("node_id=\"1\"") && l.contains("model=\"SYNCHRONOUS\""));
        assertThat(lines).filteredOn(l -> l.startsWith("distributed_replication_latency_seconds_count{"))
                .anyMatch(l -> l.contains("kind=\"SYNCHRONOUS\""));
        assertThat(lines).filteredOn(l -> l.startsWith("distributed_replication_store_items{")).hasSize(5)
                .anyMatch(l -> l.contains("node_id=\"1\"") && l.endsWith(" 1.0"));
        assertThat(lines).filteredOn(l -> l.startsWith("distributed_replication_epoch{")).hasSize(5);
    }
}
