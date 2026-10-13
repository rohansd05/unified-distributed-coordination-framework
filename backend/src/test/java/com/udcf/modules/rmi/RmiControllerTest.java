package com.udcf.modules.rmi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udcf.core.cluster.Cluster;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The /api/modules/rmi contract the E1d page relies on: every DTO's exact JSON field names and
 * types (a rename breaks a test), nulls instead of invented zeros, a remote failure as data
 * (200), the ProblemDetail bodies for 400, 404 and 409, the clean-slate cluster reset, and the
 * meters as Prometheus scrapes them. Real RMI on 127.0.0.1.
 *
 * <p>Crashes nodes, so it has its own context: {@code udcf.cluster.ports.rmi-base=28750}, so node k's
 * registry listens on 28750 + k (28751 to 28755 under the local profile's five nodes; Jai's
 * sub-range 28750-28799). Each test starts with the module idle and reset and every node up. At
 * class end the RMI services are stopped and the ports checked free, and the context is closed
 * ({@link DirtiesContext}), so no cached context keeps a listener, accept thread or RMI's reaper
 * thread for the rest of the suite.</p>
 */
@SpringBootTest(properties = "udcf.cluster.ports.rmi-base=28750")
@AutoConfigureMockMvc
@AutoConfigureObservability
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RmiControllerTest {

    private static final String BASE = "/api/modules/rmi";
    private static final int RMI_BASE = 28750;

    private static final String[] OVERVIEW_FIELDS = {"status", "actionInProgress", "boundName", "nodes", "methods",
            "limits", "payloadSizeBasis", "lookupBasis", "coHosting", "comparisonNote", "failureKinds", "latest"};
    private static final String[] NODE_FIELDS = {"nodeId", "nodeStatus", "state", "registryPort", "startFailure"};
    private static final String[] METHOD_FIELDS = {"method", "description", "parameters"};
    private static final String[] PARAMETER_FIELDS = {"name", "type", "description", "minimum", "maximum"};
    private static final String[] LIMIT_FIELDS = {"maxHashRounds", "maxEchoLength", "historySize", "callTimeoutMillis",
            "connectTimeoutMillis", "maxFailureMessageLength"};
    private static final String[] REGISTRY_FIELDS = {"nodeId", "registryPort", "state", "reachable", "names",
            "failureKind", "failureDescription", "failureClass", "failureMessage", "failureCauseClass"};
    private static final String[] CALL_FIELDS = {"id", "calledAt", "method", "fromNode", "targetNode", "path",
            "argumentSummary", "resultSummary", "outcome", "failureStage", "failureKind", "failureDescription",
            "failureClass", "failureMessage", "failureCauseClass", "lookupNanos", "roundTripNanos", "timeToFailureNanos",
            "argumentBytes", "resultBytes", "payloadSizeBasis", "stubClassName", "senderLamportAtSend", "replyLamport",
            "senderLamportAfterReply", "reply"};
    private static final String[] REPLY_FIELDS = {"method", "nodeId", "lamportTime", "registryPort", "capacityProfile",
            "uptimeMillis", "javaVersion", "text", "rounds", "seed", "digestHex", "serverNanos"};
    private static final String[] COMPARISON_FIELDS = {"id", "calledAt", "method", "fromNode", "targetNode", "local",
            "remote", "localNanos", "remoteNanos", "overheadNanos", "ratio", "digestsMatch", "note"};
    private static final String[] ENTRY_FIELDS = {"id", "kind", "call", "comparison"};
    private static final List<String> NODE_GONE = List.of("CONNECTION_REFUSED", "CONNECTION_LOST");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RmiModule module;

    @Autowired
    private Cluster cluster;

    @BeforeEach
    void cleanStart() {
        await().atMost(30, TimeUnit.SECONDS).until(() -> module.status() != ModuleStatus.BUSY);
        cluster.recoverAll();
        module.reset();
    }

    @AfterAll
    void stopEverything() {
        module.reset();
        for (int k = 1; k <= cluster.size(); k++) {
            int port = RMI_BASE + k;
            await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> !RmiTestSupport.acceptThreadAlive(port) && RmiTestSupport.canBind(port));
        }
    }

    // ------------------------------------------------------------------ helpers

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

    private JsonNode call(String body) throws Exception {
        return json(postJson("/calls", body).andExpect(status().isOk()));
    }

    private static String ping(int from, int to) {
        return "{\"fromNode\":" + from + ",\"targetNode\":" + to + ",\"method\":\"PING\"}";
    }

    private static void assertFields(JsonNode node, String... names) {
        Set<String> actual = new TreeSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).as("fields of %s", node).isEqualTo(new TreeSet<>(List.of(names)));
    }

    private static void assertType(JsonNode node, String field, Predicate<JsonNode> type) {
        assertThat(type.test(node.get(field))).as("type of %s in %s", field, node).isTrue();
    }

    private static void assertNull(JsonNode node, String... fields) {
        for (String field : fields) {
            assertThat(node.get(field).isNull()).as("%s is null in %s", field, node).isTrue();
        }
    }

    private static void assertProblem(JsonNode problem, int status, String title, String... extraFields) {
        List<String> fields = new ArrayList<>(List.of("type", "title", "status", "detail", "instance"));
        fields.addAll(List.of(extraFields));
        assertFields(problem, fields.toArray(String[]::new));
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.get("title").asText()).isEqualTo(title);
        assertType(problem, "detail", JsonNode::isTextual);
    }

    private void crash(int nodeId) throws Exception {
        mockMvc.perform(post("/api/cluster/nodes/" + nodeId + "/crash")).andExpect(status().isOk());
    }

    /** A successful REMOTE call, with every field's type and every inapplicable field null. */
    private static void assertRemoteSuccess(JsonNode call, String method) {
        assertFields(call, CALL_FIELDS);
        assertThat(call.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(call.get("path").asText()).isEqualTo("REMOTE");
        assertThat(call.get("method").asText()).isEqualTo(method);
        assertType(call, "id", JsonNode::isIntegralNumber);
        assertType(call, "calledAt", JsonNode::isTextual);
        for (String f : List.of("lookupNanos", "roundTripNanos", "argumentBytes", "resultBytes", "senderLamportAtSend",
                "replyLamport", "senderLamportAfterReply")) {
            assertType(call, f, JsonNode::isIntegralNumber);
            assertThat(call.get(f).asLong()).as(f).isPositive();
        }
        assertThat(call.get("payloadSizeBasis").asText()).isEqualTo(PayloadSizer.LABEL);
        assertThat(call.get("stubClassName").asText()).contains("$Proxy");
        assertThat(call.get("replyLamport").asLong()).isGreaterThan(call.get("senderLamportAtSend").asLong());
        assertThat(call.get("senderLamportAfterReply").asLong()).isGreaterThan(call.get("replyLamport").asLong());
        assertNull(call, "timeToFailureNanos", "failureStage", "failureKind", "failureDescription", "failureClass",
                "failureMessage", "failureCauseClass");
        assertFields(call.get("reply"), REPLY_FIELDS);
        assertThat(call.get("reply").get("method").asText()).isEqualTo(method);
        assertThat(call.get("reply").get("nodeId").asInt()).isEqualTo(call.get("targetNode").asInt());
    }

    // ------------------------------------------------------------------ overview and start

    @Test
    @DisplayName("GET overview before any start: exact fields, every node NOT_STARTED, methods, limits, texts, all failure kinds")
    void overviewBeforeStart() throws Exception {
        JsonNode overview = overview();

        assertFields(overview, OVERVIEW_FIELDS);
        assertThat(overview.get("status").asText()).isEqualTo("IDLE");
        assertNull(overview, "actionInProgress", "latest");
        assertThat(overview.get("boundName").asText()).isEqualTo("NodeEndpoint");
        assertThat(overview.get("nodes")).hasSize(5);
        for (int k = 0; k < 5; k++) {
            JsonNode node = overview.get("nodes").get(k);
            assertFields(node, NODE_FIELDS);
            assertThat(node.get("nodeId").asInt()).isEqualTo(k + 1);
            assertThat(node.get("nodeStatus").asText()).isEqualTo("UP");
            assertThat(node.get("state").asText()).isEqualTo("NOT_STARTED");
            assertThat(node.get("registryPort").asInt()).isEqualTo(RMI_BASE + k + 1);
            assertNull(node, "startFailure");
        }
        JsonNode methods = overview.get("methods");
        assertThat(methods).extracting(m -> m.get("method").asText())
                .containsExactly("PING", "NODE_INFO", "ECHO", "COMPUTE_HASH");
        methods.forEach(m -> assertFields(m, METHOD_FIELDS));
        assertThat(methods.get(0).get("parameters")).isEmpty();
        JsonNode text = methods.get(2).get("parameters").get(0);
        assertFields(text, PARAMETER_FIELDS);
        assertThat(List.of(text.get("name").asText(), text.get("type").asText(), text.get("minimum").asInt(),
                text.get("maximum").asInt())).containsExactly("text", "string", 0, 1024);
        JsonNode rounds = methods.get(3).get("parameters").get(0);
        assertThat(List.of(rounds.get("name").asText(), rounds.get("type").asText(), rounds.get("minimum").asInt(),
                rounds.get("maximum").asInt())).containsExactly("rounds", "int", 1, 1_000_000);
        JsonNode limits = overview.get("limits");
        assertFields(limits, LIMIT_FIELDS);
        assertThat(Arrays.stream(LIMIT_FIELDS).map(f -> limits.get(f).asInt()))
                .containsExactly(1_000_000, 1024, 50, 3000, 1000, 300);
        assertThat(overview.get("payloadSizeBasis").asText()).isEqualTo(PayloadSizer.LABEL);
        assertThat(overview.get("lookupBasis").asText()).isEqualTo(RmiModule.LOOKUP_BASIS);
        assertFields(overview.get("coHosting"), "simulated", "reason");
        assertThat(overview.get("coHosting").get("simulated").asBoolean()).isTrue();
        assertThat(overview.get("coHosting").get("reason").asText()).isEqualTo(RmiModule.CO_HOSTING.reason());
        assertThat(overview.get("comparisonNote").asText()).isEqualTo(RmiModule.COMPARISON_NOTE);
        assertThat(overview.get("failureKinds")).extracting(k -> k.get("kind").asText())
                .containsExactly(Arrays.stream(RemoteFailureKind.values()).map(Enum::name).toArray(String[]::new))
                .contains("CONNECTION_LOST", "ARGUMENTS_REJECTED");
        overview.get("failureKinds").forEach(k -> {
            assertFields(k, "kind", "description");
            assertThat(k.get("description").asText())
                    .isEqualTo(RemoteFailureKind.valueOf(k.get("kind").asText()).description());
        });
    }

    @Test
    @DisplayName("POST /start exports every node (RUNNING) and is idempotent")
    void startExportsEveryNode() throws Exception {
        JsonNode started = json(mockMvc.perform(post(BASE + "/start")).andExpect(status().isOk()));
        assertFields(started, OVERVIEW_FIELDS);
        assertThat(started.get("status").asText()).isEqualTo("RUNNING");
        assertThat(started.get("nodes")).extracting(n -> n.get("state").asText()).containsOnly("EXPORTED");

        JsonNode again = json(mockMvc.perform(post(BASE + "/start")).andExpect(status().isOk()));
        assertThat(again.get("nodes")).extracting(n -> n.get("state").asText()).containsOnly("EXPORTED");
    }

    // ------------------------------------------------------------------ registries

    @Test
    @DisplayName("GET /registries before start: every registry unreachable (refused), names null, state NOT_STARTED")
    void registriesBeforeStart() throws Exception {
        JsonNode registries = json(mockMvc.perform(get(BASE + "/registries")).andExpect(status().isOk()));
        assertThat(registries).hasSize(5);
        registries.forEach(r -> {
            assertFields(r, REGISTRY_FIELDS);
            assertThat(r.get("reachable").asBoolean()).isFalse();
            assertNull(r, "names");
            assertThat(r.get("failureKind").asText()).isEqualTo("CONNECTION_REFUSED");
            assertThat(r.get("state").asText()).isEqualTo("NOT_STARTED");
        });
    }

    @Test
    @DisplayName("GET /registries with every node up lists NodeEndpoint and its stub class on each node, over the network")
    void registriesAllUp() throws Exception {
        mockMvc.perform(post(BASE + "/start")).andExpect(status().isOk());

        JsonNode registries = json(mockMvc.perform(get(BASE + "/registries")).andExpect(status().isOk()));

        assertThat(registries).hasSize(5);
        for (int k = 0; k < 5; k++) {
            JsonNode r = registries.get(k);
            assertFields(r, REGISTRY_FIELDS);
            assertThat(r.get("nodeId").asInt()).isEqualTo(k + 1);
            assertThat(r.get("registryPort").asInt()).isEqualTo(RMI_BASE + k + 1);
            assertThat(r.get("state").asText()).isEqualTo("EXPORTED");
            assertThat(r.get("reachable").asBoolean()).isTrue();
            // Since E9b every node also exports Experiment 9's CollectiveEndpoint: two names, sorted.
            assertThat(r.get("names")).hasSize(2);
            for (int i = 0; i < 2; i++) {
                assertFields(r.get("names").get(i), "name", "stubClassName");
                assertThat(r.get("names").get(i).get("stubClassName").asText()).contains("$Proxy");
            }
            assertThat(r.get("names").get(0).get("name").asText()).isEqualTo("CollectiveEndpoint");
            assertThat(r.get("names").get(1).get("name").asText()).isEqualTo("NodeEndpoint");
            assertNull(r, "failureKind", "failureDescription", "failureClass", "failureMessage", "failureCauseClass");
        }
        JsonNode one = json(mockMvc.perform(get(BASE + "/registries/2")).andExpect(status().isOk()));
        assertThat(one.get("nodeId").asInt()).isEqualTo(2);
        assertThat(one.get("reachable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a crashed node's registry: reachable false, names null (never []), the classified failure with its description")
    void registryOfACrashedNode() throws Exception {
        mockMvc.perform(post(BASE + "/start")).andExpect(status().isOk());
        crash(3);

        JsonNode r = json(mockMvc.perform(get(BASE + "/registries/3")).andExpect(status().isOk()));

        assertThat(r.get("state").asText()).isEqualTo("CRASHED");
        assertThat(r.get("reachable").asBoolean()).isFalse();
        assertNull(r, "names");
        assertThat(r.get("failureKind").asText()).isIn(NODE_GONE);
        assertThat(r.get("failureDescription").asText())
                .isEqualTo(RemoteFailureKind.valueOf(r.get("failureKind").asText()).description());
        assertType(r, "failureClass", JsonNode::isTextual);
        JsonNode all = json(mockMvc.perform(get(BASE + "/registries")).andExpect(status().isOk()));
        assertThat(all).extracting(n -> n.get("reachable").asBoolean()).containsExactly(true, true, false, true, true);
    }

    @Test
    @DisplayName("GET /registries/9 is 404 Unknown node")
    void registryOfAnUnknownNode() throws Exception {
        JsonNode problem = json(mockMvc.perform(get(BASE + "/registries/9")).andExpect(status().isNotFound()));
        assertProblem(problem, 404, "Unknown node", "nodeId");
        assertThat(problem.get("nodeId").asInt()).isEqualTo(9);
    }

    // ------------------------------------------------------------------ calls

    static Stream<Arguments> everyMethod() {
        return Stream.of(
                Arguments.of("PING", ping(1, 2), 2),
                Arguments.of("NODE_INFO", "{\"fromNode\":1,\"targetNode\":3,\"method\":\"NODE_INFO\"}", 3),
                Arguments.of("ECHO", "{\"fromNode\":1,\"targetNode\":4,\"method\":\"ECHO\",\"text\":\"hello\"}", 4),
                Arguments.of("COMPUTE_HASH", "{\"fromNode\":1,\"targetNode\":5,\"method\":\"COMPUTE_HASH\",\"rounds\":1000}", 5));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyMethod")
    @DisplayName("POST /calls succeeds for each method (lazy start): every field, its type, its own reply fields, the rest null")
    void callEachMethod(String method, String body, int target) throws Exception {
        JsonNode call = call(body);

        assertRemoteSuccess(call, method);
        assertThat(call.get("fromNode").asInt()).isEqualTo(1);
        assertThat(call.get("targetNode").asInt()).isEqualTo(target);
        JsonNode reply = call.get("reply");
        List<String> own = switch (method) {
            case "PING" -> List.of();
            case "NODE_INFO" -> List.of("registryPort", "capacityProfile", "uptimeMillis", "javaVersion");
            case "ECHO" -> List.of("text");
            default -> List.of("rounds", "seed", "digestHex", "serverNanos");
        };
        for (String f : List.of("registryPort", "capacityProfile", "uptimeMillis", "javaVersion", "text", "rounds",
                "seed", "digestHex", "serverNanos")) {
            assertThat(reply.get(f).isNull()).as("%s null for %s", f, method).isEqualTo(!own.contains(f));
        }
        if (method.equals("NODE_INFO")) {
            assertThat(reply.get("registryPort").asInt()).isEqualTo(RMI_BASE + 3);
            assertThat(reply.get("capacityProfile").asText()).isEqualTo("SLOW");
        }
        if (method.equals("COMPUTE_HASH")) {
            assertThat(reply.get("digestHex").asText()).hasSize(64);
            assertThat(reply.get("rounds").asInt()).isEqualTo(1000);
        }
        assertThat(overview().get("status").asText()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("a call to a crashed node is 200 with REMOTE_FAILURE as data: kind, description, time to failure; no round trip")
    void callToACrashedNodeIsData() throws Exception {
        mockMvc.perform(post(BASE + "/start")).andExpect(status().isOk());
        crash(2);

        JsonNode call = call(ping(1, 2));

        assertFields(call, CALL_FIELDS);
        assertThat(call.get("outcome").asText()).isEqualTo("REMOTE_FAILURE");
        assertThat(call.get("failureKind").asText()).isIn(NODE_GONE);
        assertThat(call.get("failureDescription").asText())
                .isEqualTo(RemoteFailureKind.valueOf(call.get("failureKind").asText()).description());
        assertThat(call.get("failureStage").asText()).isIn("LOOKUP", "CALL");
        assertType(call, "failureClass", JsonNode::isTextual);
        assertThat(call.get("timeToFailureNanos").asLong()).isPositive();
        assertNull(call, "roundTripNanos", "resultBytes", "replyLamport", "senderLamportAfterReply", "reply",
                "resultSummary");
    }

    @Test
    @DisplayName("a call from a crashed node is 409 Node down: a dead node cannot call")
    void callFromACrashedNode() throws Exception {
        crash(4);
        JsonNode problem = json(postJson("/calls", ping(4, 1)).andExpect(status().isConflict()));
        assertProblem(problem, 409, "Node down", "nodeId");
        assertThat(problem.get("nodeId").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("an unknown calling or target node is 404 Unknown node")
    void unknownNodes() throws Exception {
        JsonNode from = json(postJson("/calls", ping(9, 1)).andExpect(status().isNotFound()));
        assertProblem(from, 404, "Unknown node", "nodeId");
        assertThat(from.get("nodeId").asInt()).isEqualTo(9);
        JsonNode to = json(postJson("/comparisons", ping(1, 0)).andExpect(status().isNotFound()));
        assertThat(to.get("nodeId").asInt()).isEqualTo(0);
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("{}", List.of("fromNode", "targetNode", "method")),
                Arguments.of("{\"fromNode\":1,\"targetNode\":2,\"method\":\"PING\",\"text\":\"x\"}", List.of("text")),
                Arguments.of("{\"fromNode\":1,\"targetNode\":2,\"method\":\"NODE_INFO\",\"rounds\":5}", List.of("rounds")),
                Arguments.of("{\"fromNode\":1,\"targetNode\":2,\"method\":\"PING\",\"text\":\"\",\"rounds\":1}",
                        List.of("text", "rounds")),
                Arguments.of("{\"fromNode\":1,\"targetNode\":2,\"method\":\"ECHO\"}", List.of("text")),
                Arguments.of("{\"fromNode\":1,\"targetNode\":2,\"method\":\"ECHO\",\"text\":\"" + "e".repeat(1025) + "\"}",
                        List.of("text")),
                Arguments.of("{\"fromNode\":1,\"targetNode\":2,\"method\":\"ECHO\",\"text\":\"a\",\"rounds\":1}",
                        List.of("rounds")),
                Arguments.of("{\"fromNode\":1,\"targetNode\":2,\"method\":\"COMPUTE_HASH\"}", List.of("rounds")),
                Arguments.of("{\"fromNode\":1,\"targetNode\":2,\"method\":\"COMPUTE_HASH\",\"rounds\":0}", List.of("rounds")),
                Arguments.of("{\"fromNode\":1,\"targetNode\":2,\"method\":\"COMPUTE_HASH\",\"rounds\":1000001}",
                        List.of("rounds")),
                Arguments.of("{\"fromNode\":1,\"targetNode\":2,\"method\":\"COMPUTE_HASH\",\"rounds\":3,\"text\":\"t\"}",
                        List.of("text")));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    @DisplayName("400 Invalid request parameters with a message for every offending field, on calls and comparisons")
    void invalidBodies(String body, List<String> fields) throws Exception {
        for (String path : List.of("/calls", "/comparisons")) {
            JsonNode problem = json(postJson(path, body).andExpect(status().isBadRequest()));
            assertProblem(problem, 400, "Invalid request parameters", "errors");
            assertThat(problem.get("errors").properties()).extracting(e -> e.getKey())
                    .containsExactlyInAnyOrderElementsOf(fields);
        }
        assertThat(module.history()).isEmpty();
    }

    @Test
    @DisplayName("an unreadable body (unknown method) and a non-numeric id are Spring's default 400, without errors")
    void defaultBadRequests() throws Exception {
        JsonNode unreadable = json(postJson("/calls", "{\"fromNode\":1,\"targetNode\":2,\"method\":\"FOO\"}")
                .andExpect(status().isBadRequest()));
        assertThat(unreadable.get("status").asInt()).isEqualTo(400);
        assertThat(unreadable.has("errors")).isFalse();
        JsonNode badId = json(mockMvc.perform(get(BASE + "/calls/abc")).andExpect(status().isBadRequest()));
        assertThat(badId.get("status").asInt()).isEqualTo(400);
        assertThat(badId.has("errors")).isFalse();
    }

    @Test
    @DisplayName("409 Module busy on start, calls and comparisons while an action holds the guard, naming it")
    void busy() throws Exception {
        try (ModuleActionGuard.ActionTicket ignored = module.guard().begin("PING from node 1 to node 2")) {
            assertThat(overview().get("status").asText()).isEqualTo("BUSY");
            assertThat(overview().get("actionInProgress").asText()).isEqualTo("PING from node 1 to node 2");
            for (ResultActions result : List.of(postJson("/calls", ping(1, 2)), postJson("/comparisons", ping(1, 2)),
                    mockMvc.perform(post(BASE + "/start")))) {
                JsonNode problem = json(result.andExpect(status().isConflict()));
                assertProblem(problem, 409, "Module busy", "moduleId", "actionInProgress");
                assertThat(problem.get("moduleId").asText()).isEqualTo("rmi");
                assertThat(problem.get("actionInProgress").asText()).isEqualTo("PING from node 1 to node 2");
            }
        }
    }

    @Test
    @DisplayName("a node may call itself: a call and a comparison with the same node on both sides are accepted")
    void selfCalls() throws Exception {
        JsonNode call = call(ping(3, 3));
        assertRemoteSuccess(call, "PING");
        assertThat(call.get("fromNode").asInt()).isEqualTo(3);
        assertThat(call.get("targetNode").asInt()).isEqualTo(3);

        JsonNode compared = json(postJson("/comparisons", ping(3, 3)).andExpect(status().isOk()));
        assertThat(compared.get("local").get("path").asText()).isEqualTo("LOCAL");
        assertThat(compared.get("local").get("stubClassName").asText()).isEqualTo(NodeEndpointLogic.class.getName());
        assertThat(compared.get("remote").get("path").asText()).isEqualTo("REMOTE");
        assertThat(compared.get("remote").get("stubClassName").asText()).contains("$Proxy");
    }

    // ------------------------------------------------------------------ comparisons

    @Test
    @DisplayName("POST /comparisons: both halves under one id, local marshals nothing, overhead, ratio, digestsMatch, note")
    void comparison() throws Exception {
        JsonNode c = json(postJson("/comparisons",
                "{\"fromNode\":1,\"targetNode\":2,\"method\":\"COMPUTE_HASH\",\"rounds\":2000}").andExpect(status().isOk()));

        assertFields(c, COMPARISON_FIELDS);
        assertFields(c.get("local"), CALL_FIELDS);
        assertRemoteSuccess(c.get("remote"), "COMPUTE_HASH");
        JsonNode local = c.get("local");
        assertThat(local.get("path").asText()).isEqualTo("LOCAL");
        assertThat(local.get("outcome").asText()).isEqualTo("SUCCESS");
        assertNull(local, "argumentBytes", "resultBytes", "payloadSizeBasis", "timeToFailureNanos");
        assertThat(local.get("id").asLong()).isEqualTo(c.get("id").asLong());
        assertThat(c.get("remote").get("id").asLong()).isEqualTo(c.get("id").asLong());
        assertThat(c.get("localNanos").asLong()).isEqualTo(local.get("roundTripNanos").asLong());
        assertThat(c.get("remoteNanos").asLong()).isEqualTo(c.get("remote").get("roundTripNanos").asLong());
        assertThat(c.get("overheadNanos").asLong())
                .isEqualTo(c.get("remoteNanos").asLong() - c.get("localNanos").asLong());
        assertType(c, "ratio", JsonNode::isNumber);
        assertThat(c.get("digestsMatch").asBoolean()).isTrue();
        assertThat(c.get("note").asText()).isEqualTo(RmiModule.COMPARISON_NOTE);

        JsonNode p = json(postJson("/comparisons", ping(1, 2)).andExpect(status().isOk()));
        assertNull(p, "digestsMatch");
    }

    @Test
    @DisplayName("a comparison against a crashed target is 409 Node down (the local half cannot run); from a crashed caller too")
    void comparisonAgainstACrashedTarget() throws Exception {
        crash(2);
        JsonNode target = json(postJson("/comparisons", ping(1, 2)).andExpect(status().isConflict()));
        assertProblem(target, 409, "Node down", "nodeId");
        assertThat(target.get("nodeId").asInt()).isEqualTo(2);
        JsonNode caller = json(postJson("/comparisons", ping(2, 1)).andExpect(status().isConflict()));
        assertThat(caller.get("nodeId").asInt()).isEqualTo(2);
        assertThat(module.history()).isEmpty();
    }

    // ------------------------------------------------------------------ history

    @Test
    @DisplayName("history: 404 before any call; then newest first, latest, by id, 404 for an unknown id; one id sequence")
    void history() throws Exception {
        JsonNode none = json(mockMvc.perform(get(BASE + "/calls/latest")).andExpect(status().isNotFound()));
        assertProblem(none, 404, "Unknown call", "callId");
        assertThat(none.get("callId").isNull()).isTrue();
        assertThat(json(mockMvc.perform(get(BASE + "/calls")).andExpect(status().isOk()))).isEmpty();

        call(ping(1, 2));
        json(postJson("/comparisons", ping(1, 3)).andExpect(status().isOk()));

        JsonNode all = json(mockMvc.perform(get(BASE + "/calls")).andExpect(status().isOk()));
        assertThat(all).hasSize(2);
        all.forEach(e -> assertFields(e, ENTRY_FIELDS));
        assertThat(all).extracting(e -> e.get("id").asLong()).containsExactly(2L, 1L);
        assertThat(all).extracting(e -> e.get("kind").asText()).containsExactly("COMPARISON", "CALL");
        assertNull(all.get(0), "call");
        assertNull(all.get(1), "comparison");
        assertThat(json(mockMvc.perform(get(BASE + "/calls/latest")).andExpect(status().isOk())).get("id").asLong())
                .isEqualTo(2);
        JsonNode first = json(mockMvc.perform(get(BASE + "/calls/1")).andExpect(status().isOk()));
        assertThat(first.get("kind").asText()).isEqualTo("CALL");
        assertRemoteSuccess(first.get("call"), "PING");
        JsonNode unknown = json(mockMvc.perform(get(BASE + "/calls/99")).andExpect(status().isNotFound()));
        assertThat(unknown.get("callId").asLong()).isEqualTo(99);
        assertThat(overview().get("latest").get("id").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("POST /api/cluster/reset is a clean slate for this module: IDLE, every node NOT_STARTED, no history, ids from 1")
    void clusterReset() throws Exception {
        call(ping(1, 2));
        call(ping(2, 1));

        mockMvc.perform(post("/api/cluster/reset")).andExpect(status().isNoContent());

        JsonNode overview = overview();
        assertThat(overview.get("status").asText()).isEqualTo("IDLE");
        assertThat(overview.get("nodes")).extracting(n -> n.get("state").asText()).containsOnly("NOT_STARTED");
        assertNull(overview, "latest");
        assertThat(json(mockMvc.perform(get(BASE + "/calls")).andExpect(status().isOk()))).isEmpty();
        assertThat(call(ping(1, 2)).get("id").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("Prometheus scrapes the rmi meters with node_id: calls, round trips, exported endpoints")
    void prometheus() throws Exception {
        call(ping(1, 2));

        String scrape = mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(scrape.lines().filter(l -> l.startsWith("distributed_rmi_calls_total{"))).anySatisfy(l ->
                assertThat(l).contains("node_id=\"1\"").contains("target_node=\"2\"").contains("outcome=\"SUCCESS\""));
        assertThat(scrape.lines().filter(l -> l.startsWith("distributed_rmi_round_trip_seconds_count{")))
                .anySatisfy(l -> assertThat(l).contains("node_id=\"1\""));
        assertThat(scrape.lines().filter(l -> l.startsWith("distributed_rmi_endpoint_exported{")))
                .hasSize(5).allSatisfy(l -> assertThat(l).contains("node_id=\"").endsWith(" 1.0"));
    }
}
