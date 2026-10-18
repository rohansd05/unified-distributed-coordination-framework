package com.udcf.modules.mpi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.cluster.LeaderElector;
import com.udcf.core.cluster.RoleProvider;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.rmi.PayloadSizer;
import com.udcf.modules.rmi.RmiModule;
import com.udcf.modules.rmi.RmiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The /api/modules/mpi contract the E9d page relies on: every DTO's exact JSON field names and
 * types (a rename breaks a test), nulls instead of invented zeros, every outcome as 200, the
 * ProblemDetail bodies for 400, 404 and 409, the module in GET /api/modules, and the meters as
 * Prometheus scrapes them. Real RMI on 127.0.0.1.
 *
 * <p>Crashes nodes, so it has its own context with Jai's test ports: {@code rmi-base=28660}, so node
 * k's registry listens on 28660 + k (TCP 28661 to 28665 under the local profile's five nodes), and
 * {@code clock-base=28670}, because the Experiment 3 module starts its UDP clock service on every live
 * node when the context starts (UDP 28671 to 28675). No other service starts (checked): every collective here
 * names root node 1 (REQUESTED), so none asks Experiment 4 for a leader; the leader as the default root is
 * covered by MpiModuleTest and LeaderRolesIntegrationTest ({@code election-base=28130} is pinned anyway). Each test
 * starts with a cluster reset (every node up, RMI stopped, history and events cleared). At class end
 * the RMI services are stopped and the ports checked free, and the context is closed.</p>
 */
@SpringBootTest(properties = {"udcf.cluster.ports.rmi-base=28660", "udcf.cluster.ports.clock-base=28670",
        "udcf.cluster.ports.election-base=28130"})
@AutoConfigureMockMvc
@AutoConfigureObservability
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MpiControllerTest {

    private static final String BASE = "/api/modules/mpi";
    private static final int RMI_BASE = 28660;

    private static final String[] OVERVIEW_FIELDS = {"status", "actionInProgress", "boundName", "operations", "limits",
            "participants", "defaultRootNodeId", "defaultRootSource", "latencyBasis", "payloadSizeBasis", "algorithmNote",
            "coHosting", "latest"};
    private static final String[] OPERATION_FIELDS = {"operation", "description", "minimumCount"};
    private static final String[] LIMIT_FIELDS = {"maxPayloadElements", "collectiveDeadlineMillis", "historySize",
            "maxSeed", "connectTimeoutMillis", "callTimeoutMillis", "worstCaseTransferMillis",
            "recommendedMinClientTimeoutMillis"};
    private static final String[] NODE_FIELDS = {"nodeId", "nodeStatus", "rmiState", "registryPort", "startFailure",
            "collectiveEndpointExported", "leader", "storedCollectiveIds"};
    private static final String[] COLLECTIVE_FIELDS = {"id", "startedAt", "operation", "rootNodeId", "rootSource",
            "rootRank", "rankNodeIds", "skippedNodeIds", "count", "seed", "outcome", "refusal", "inputChecksum",
            "totalNanos", "deadlineMillis", "transfers", "gathered", "verification", "payloadSizeBasis", "latencyBasis"};
    private static final String[] TRANSFER_FIELDS = {"rank", "fromNodeId", "toNodeId", "direction", "status", "offset",
            "elementCount", "latencyNanos", "timeToFailureNanos", "requestBytes", "replyBytes", "expectedChecksum",
            "reportedChecksum", "verified", "failureStage", "failureKind", "failureDescription", "failureClass",
            "failureMessage", "failureCauseClass", "senderLamportAtSend", "replyLamport", "lamportAfterReply",
            "notAttemptedReason"};
    private static final String[] VERIFICATION_FIELDS = {"passed", "checked", "matched", "mismatched", "unknown",
            "wholeVectorMatches"};
    private static final String[] BLOCK_FIELDS = {"rank", "nodeId", "offset", "length", "checksum", "hole"};
    private static final String[] TRANSFER_NUMBERS = {"latencyNanos", "timeToFailureNanos", "requestBytes", "replyBytes",
            "senderLamportAtSend", "replyLamport", "lamportAfterReply"};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MpiModule module;

    @Autowired
    private RmiModule rmiModule;

    @Autowired
    private Cluster cluster;

    @BeforeEach
    void cleanStart() throws Exception {
        await().atMost(30, TimeUnit.SECONDS).until(() -> module.status() != ModuleStatus.BUSY);
        mockMvc.perform(post("/api/cluster/reset")).andExpect(status().isNoContent());
    }

    @AfterAll
    void stopEverything() {
        rmiModule.reset();
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

    private ResultActions postCollective(String body) throws Exception {
        return mockMvc.perform(post(BASE + "/collectives")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode run(String body) throws Exception {
        return json(postCollective(body).andExpect(status().isOk()));
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
        mockMvc.perform(post(
                "/api/cluster/nodes/" + nodeId + "/crash")).andExpect(status().isOk());
    }

    private static JsonNode transferTo(JsonNode collective, int nodeId) {
        for (JsonNode t : collective.get("transfers")) {
            if (!"LOCAL".equals(t.get("status").asText())
                    && (t.get("toNodeId").asInt() == nodeId || t.get("fromNodeId").asInt() == nodeId)) {
                return t;
            }
        }
        throw new AssertionError("no transfer for node " + nodeId);
    }

    /** A COMPLETE collective over the five nodes from node 1 (requested), with every field's type. */
    private static void assertComplete(JsonNode c, String operation) {
        assertFields(c, COLLECTIVE_FIELDS);
        assertThat(c.get("operation").asText()).isEqualTo(operation);
        assertThat(c.get("outcome").asText()).isEqualTo("COMPLETE");
        assertType(c, "id", JsonNode::isIntegralNumber);
        assertType(c, "startedAt", JsonNode::isTextual);
        assertType(c, "seed", JsonNode::isIntegralNumber);
        assertType(c, "totalNanos", JsonNode::isIntegralNumber);
        assertThat(c.get("rootNodeId").asInt()).isEqualTo(1);
        assertThat(c.get("rootSource").asText()).isEqualTo("REQUESTED");
        assertThat(c.get("rootRank").asInt()).isZero();
        assertThat(c.get("rankNodeIds").toString()).isEqualTo("[1,2,3,4,5]");
        assertThat(c.get("skippedNodeIds").toString()).isEqualTo("[]");
        assertThat(c.get("deadlineMillis").asLong()).isEqualTo(10_000);
        assertNull(c, "refusal");
        assertThat(c.get("inputChecksum").asText()).matches("[0-9a-f]{64}");
        assertThat(c.get("payloadSizeBasis").asText()).isEqualTo(PayloadSizer.LABEL);
        assertThat(c.get("latencyBasis").asText()).isEqualTo(RmiCollectiveTransport.LATENCY_BASIS);
        assertFields(c.get("verification"), VERIFICATION_FIELDS);
        assertThat(c.get("verification").get("passed").asBoolean()).isTrue();
        assertThat(c.get("verification").get("checked").asInt()).isEqualTo(4);

        JsonNode local = c.get("transfers").get(0);
        assertFields(local, TRANSFER_FIELDS);
        assertThat(local.get("status").asText()).isEqualTo("LOCAL");
        assertNull(local, TRANSFER_NUMBERS);
        assertNull(local, "failureStage", "failureKind", "failureDescription", "failureClass", "failureMessage",
                "failureCauseClass", "notAttemptedReason", "reportedChecksum");
        for (int k = 2; k <= 5; k++) {
            JsonNode t = transferTo(c, k);
            assertFields(t, TRANSFER_FIELDS);
            assertThat(t.get("status").asText()).isEqualTo("DELIVERED");
            assertThat(t.get("verified").asBoolean()).isTrue();
            for (String f : List.of("latencyNanos", "requestBytes", "replyBytes", "senderLamportAtSend", "replyLamport",
                    "lamportAfterReply")) {
                assertType(t, f, JsonNode::isIntegralNumber);
                assertThat(t.get(f).asLong()).as(f).isPositive();
            }
            assertNull(t, "timeToFailureNanos", "failureStage", "failureKind", "failureDescription", "failureClass",
                    "failureMessage", "failureCauseClass", "notAttemptedReason");
        }
    }

    // ------------------------------------------------------------------ overview and start

    @Test
    @DisplayName("GET overview before any start: exact fields, every node NOT_STARTED and unexported (stored ids null), limits, texts")
    void overviewBeforeStart() throws Exception {
        JsonNode overview = overview();

        assertFields(overview, OVERVIEW_FIELDS);
        assertThat(overview.get("status").asText()).isEqualTo("IDLE");
        assertNull(overview, "actionInProgress", "latest");
        assertThat(overview.get("boundName").asText()).isEqualTo("CollectiveEndpoint");
        assertNull(overview, "defaultRootNodeId", "defaultRootSource");   // no leader after a reset; a read never elects
        assertThat(overview.get("latencyBasis").asText()).isEqualTo(RmiCollectiveTransport.LATENCY_BASIS);
        assertThat(overview.get("payloadSizeBasis").asText()).isEqualTo(PayloadSizer.LABEL);
        assertThat(overview.get("algorithmNote").asText()).isEqualTo(MpiModule.ALGORITHM_NOTE);
        assertThat(overview.get("coHosting").get("simulated").asBoolean()).isTrue();
        assertThat(overview.get("operations")).hasSize(3);
        overview.get("operations").forEach(op -> assertFields(op, OPERATION_FIELDS));
        assertThat(overview.get("operations").get(1).get("minimumCount").asInt()).isEqualTo(5);

        JsonNode limits = overview.get("limits");
        assertFields(limits, LIMIT_FIELDS);
        assertThat(limits.get("maxPayloadElements").asInt()).isEqualTo(100_000);
        assertThat(limits.get("collectiveDeadlineMillis").asInt()).isEqualTo(10_000);
        assertThat(limits.get("historySize").asInt()).isEqualTo(20);
        assertThat(limits.get("maxSeed").asLong()).isEqualTo(9_007_199_254_740_991L);
        assertThat(limits.get("connectTimeoutMillis").asInt()).isEqualTo(1000);
        assertThat(limits.get("callTimeoutMillis").asInt()).isEqualTo(3000);
        assertThat(limits.get("worstCaseTransferMillis").asLong()).isEqualTo(12_000);
        assertThat(limits.get("recommendedMinClientTimeoutMillis").asLong()).isEqualTo(22_000);

        assertThat(overview.get("participants")).hasSize(5);
        for (JsonNode node : overview.get("participants")) {
            assertFields(node, NODE_FIELDS);
            assertThat(node.get("rmiState").asText()).isEqualTo("NOT_STARTED");
            assertThat(node.get("collectiveEndpointExported").asBoolean()).isFalse();
            assertThat(node.get("leader").asBoolean()).isFalse();
            assertThat(node.get("registryPort").asInt()).isEqualTo(RMI_BASE + node.get("nodeId").asInt());
            assertNull(node, "startFailure", "storedCollectiveIds");
        }
    }

    @Test
    @DisplayName("POST start is idempotent: every node EXPORTED with an empty store, RUNNING, one export event per node")
    void start() throws Exception {
        JsonNode first = json(mockMvc.perform(post(
                BASE + "/start")).andExpect(status().isOk()));
        JsonNode second = json(mockMvc.perform(post(
                BASE + "/start")).andExpect(status().isOk()));

        for (JsonNode overview : List.of(first, second)) {
            assertFields(overview, OVERVIEW_FIELDS);
            assertThat(overview.get("status").asText()).isEqualTo("RUNNING");
            for (JsonNode node : overview.get("participants")) {
                assertThat(node.get("rmiState").asText()).isEqualTo("EXPORTED");
                assertThat(node.get("collectiveEndpointExported").asBoolean()).isTrue();
                assertThat(node.get("storedCollectiveIds").toString()).isEqualTo("[]");
            }
        }
        JsonNode events = json(mockMvc.perform(get("/api/events").param("module", "mpi").param("limit", "100")));
        assertThat(events).hasSize(5);
        events.forEach(e -> assertThat(e.get("type").asText()).isEqualTo("COLLECTIVE_ENDPOINT_EXPORTED"));
    }

    // ------------------------------------------------------------------ collectives

    @Test
    @DisplayName("POST broadcast and scatter: 200 COMPLETE with exact fields; LOCAL has no number; delivered parts are verified")
    void broadcastAndScatter() throws Exception {
        assertComplete(run("{\"rootNode\":1,\"operation\":\"BROADCAST\",\"count\":1000,\"seed\":42}"), "BROADCAST");
        JsonNode scatter = run("{\"rootNode\":1,\"operation\":\"SCATTER\",\"count\":1003}");
        assertComplete(scatter, "SCATTER");
        assertThat(scatter.get("seed").asLong()).isEqualTo(scatter.get("id").asLong());
        assertThat(scatter.get("verification").get("wholeVectorMatches").asBoolean()).isTrue();
        assertThat(scatter.get("gathered").toString()).isEqualTo("[]");
    }

    @Test
    @DisplayName("POST gather: blocks with checksums, no values on the wire, the assembled vector verified")
    void gather() throws Exception {
        JsonNode gather = run("{\"rootNode\":1,\"operation\":\"GATHER\",\"count\":1003,\"seed\":42}");

        assertComplete(gather, "GATHER");
        assertThat(gather.get("gathered")).hasSize(5);
        for (JsonNode block : gather.get("gathered")) {
            assertFields(block, BLOCK_FIELDS);
            assertThat(block.get("hole").asBoolean()).isFalse();
            assertThat(block.get("checksum").asText()).matches("[0-9a-f]{64}");
        }
        assertThat(gather.get("verification").get("wholeVectorMatches").asBoolean()).isTrue();
        assertThat(transferTo(gather, 2).get("direction").asText()).isEqualTo("FROM_RANK");
    }

    @Test
    @DisplayName("POST scatter with fewer elements than live nodes: 200 REFUSED with the refusal, no transfers, null rank and verification")
    void refused() throws Exception {
        JsonNode refused = run("{\"rootNode\":1,\"operation\":\"SCATTER\",\"count\":4}");

        assertFields(refused, COLLECTIVE_FIELDS);
        assertThat(refused.get("outcome").asText()).isEqualTo("REFUSED");
        assertFields(refused.get("refusal"), "reason", "message");
        assertThat(refused.get("refusal").get("reason").asText()).isEqualTo("COUNT_BELOW_PARTICIPANTS");
        assertThat(refused.get("transfers").toString()).isEqualTo("[]");
        assertNull(refused, "rootRank", "inputChecksum", "totalNanos", "verification", "payloadSizeBasis", "latencyBasis");
        assertThat(json(mockMvc.perform(get(BASE + "/collectives/latest"))).get("outcome").asText()).isEqualTo("REFUSED");
    }

    @Test
    @DisplayName("history, latest and by id: newest first; 404 Unknown collective before any and for an unknown id; 400 for a bad id")
    void history() throws Exception {
        JsonNode none = json(mockMvc.perform(get(BASE + "/collectives/latest")).andExpect(status().isNotFound()));
        assertProblem(none, 404, "Unknown collective", "collectiveId");
        assertThat(none.get("collectiveId").isNull()).isTrue();
        assertThat(json(mockMvc.perform(get(BASE + "/collectives")).andExpect(status().isOk())).toString()).isEqualTo("[]");

        run("{\"rootNode\":1,\"operation\":\"BROADCAST\",\"count\":10}");
        run("{\"rootNode\":1,\"operation\":\"GATHER\",\"count\":10}");

        JsonNode list = json(mockMvc.perform(get(BASE + "/collectives")).andExpect(status().isOk()));
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("id").asLong()).isEqualTo(2);
        assertThat(list.get(1).get("id").asLong()).isEqualTo(1);
        assertFields(list.get(0), COLLECTIVE_FIELDS);
        assertThat(json(mockMvc.perform(get(BASE + "/collectives/latest")).andExpect(status().isOk())).get("id").asLong())
                .isEqualTo(2);
        assertThat(json(mockMvc.perform(get(BASE + "/collectives/1")).andExpect(status().isOk())).get("operation").asText())
                .isEqualTo("BROADCAST");
        assertThat(overview().get("latest").get("id").asLong()).isEqualTo(2);

        JsonNode unknown = json(mockMvc.perform(get(BASE + "/collectives/999")).andExpect(status().isNotFound()));
        assertProblem(unknown, 404, "Unknown collective", "collectiveId");
        assertThat(unknown.get("collectiveId").asLong()).isEqualTo(999);

        JsonNode badId = json(mockMvc.perform(get(BASE + "/collectives/abc")).andExpect(status().isBadRequest()));
        assertProblem(badId, 400, "Bad Request");
    }

    // ------------------------------------------------------------------ errors

    @Test
    @DisplayName("400 with every field at once; count and seed ranges; an unreadable body gets Spring's 400 without errors")
    void badRequests() throws Exception {
        JsonNode missing = json(postCollective("{}").andExpect(status().isBadRequest()));
        assertProblem(missing, 400, "Invalid request parameters", "errors");
        assertThat(missing.get("errors").toString())
                .isEqualTo("{\"operation\":\"operation is required\",\"count\":\"count is required\"}");

        JsonNode count = json(postCollective("{\"operation\":\"BROADCAST\",\"count\":100001}").andExpect(status().isBadRequest()));
        assertThat(count.get("errors").get("count").asText()).isEqualTo("count must be 1-100000, was 100001");

        JsonNode seed = json(postCollective("{\"operation\":\"BROADCAST\",\"count\":5,\"seed\":-1}").andExpect(status().isBadRequest()));
        assertThat(seed.get("errors").get("seed").asText()).isEqualTo("seed must be 0-9007199254740991, was -1");

        JsonNode unreadable = json(postCollective("{\"operation\":\"FOO\",\"count\":5}").andExpect(status().isBadRequest()));
        assertProblem(unreadable, 400, "Bad Request");
        assertThat(module.history()).isEmpty();
    }

    @Test
    @DisplayName("404 Unknown node for a root that does not exist")
    void unknownRoot() throws Exception {
        JsonNode problem = json(postCollective("{\"operation\":\"BROADCAST\",\"count\":5,\"rootNode\":9}").andExpect(status().isNotFound()));
        assertProblem(problem, 404, "Unknown node", "nodeId");
        assertThat(problem.get("nodeId").asInt()).isEqualTo(9);
    }

    @Test
    @DisplayName("409 Module busy while an action holds the guard, with actionInProgress; the overview says BUSY")
    void busy() throws Exception {
        ModuleActionGuard.ActionTicket held = module.guard().begin("BROADCAST of 100000 values");
        try {
            JsonNode problem = json(postCollective("{\"rootNode\":1,\"operation\":\"GATHER\",\"count\":5}").andExpect(status().isConflict()));
            assertProblem(problem, 409, "Module busy", "moduleId", "actionInProgress");
            assertThat(problem.get("moduleId").asText()).isEqualTo("mpi");
            assertThat(problem.get("actionInProgress").asText()).isEqualTo("BROADCAST of 100000 values");
            assertThat(overview().get("status").asText()).isEqualTo("BUSY");
        } finally {
            held.close();
        }
    }

    @Test
    @DisplayName("409 Node down for a crashed requested root; 409 Node state conflict (node 0) when every node is crashed")
    void nodeDown() throws Exception {
        crash(3);
        JsonNode down = json(postCollective("{\"operation\":\"BROADCAST\",\"count\":5,\"rootNode\":3}").andExpect(status().isConflict()));
        assertProblem(down, 409, "Node down", "nodeId");
        assertThat(down.get("nodeId").asInt()).isEqualTo(3);

        for (int k : List.of(1, 2, 4, 5)) {
            crash(k);
        }
        JsonNode none = json(postCollective("{\"operation\":\"BROADCAST\",\"count\":5}").andExpect(status().isConflict()));
        assertProblem(none, 409, "Node state conflict", "nodeId");
        assertThat(none.get("nodeId").asInt()).isZero();
        assertThat(module.history()).isEmpty();
    }

    @Test
    @DisplayName("409 No leader, reason NO_LEADER, when live nodes exist but no leader could be elected")
    void noLeader() throws Exception {
        // A running backend always elects a live node, so this module has no election module at all (its
        // RoleProvider answers empty at once); its nodes bind nothing (bases 28460 to 28490 and 28360 to 28370).
        ClusterEventBus bus = RmiTestSupport.bus();
        Cluster alone = new Cluster(new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
                new ClusterProperties.Ports(28460, 28470, 28480, 28490, 28360, 28370)), bus);
        RoleProvider none = new RoleProvider(alone, (LeaderElector) null);
        MpiModule leaderless = new MpiModule(alone, bus, MpiTestResults.PROPERTIES, rmiModule,
                RmiTestSupport.PROPERTIES, RmiTestSupport.TRANSPORT, new SimpleMeterRegistry(), Clock.systemUTC(), none);
        try {
            MockMvcBuilders.standaloneSetup(new MpiController(leaderless)).build()
                    .perform(post(BASE + "/collectives").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"operation\":\"BROADCAST\",\"count\":5}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("No leader"))
                    .andExpect(jsonPath("$.reason").value("NO_LEADER"))
                    .andExpect(jsonPath("$.moduleId").value("mpi"))
                    .andExpect(jsonPath("$.detail").value(new NoLeaderException("a collective").getMessage()));
            assertThat(leaderless.history()).isEmpty();
        } finally {
            leaderless.close();
            none.close();
            alone.close();
            bus.close();
        }
    }

    // ------------------------------------------------------------------ registry, services, meters

    @Test
    @DisplayName("GET /api/modules lists mpi as lab 9")
    void listedAsLab9() throws Exception {
        JsonNode modules = json(mockMvc.perform(get("/api/modules")).andExpect(status().isOk()));
        List<JsonNode> mpi = new ArrayList<>();
        modules.forEach(m -> {
            if ("mpi".equals(m.get("id").asText())) {
                mpi.add(m);
            }
        });
        assertThat(mpi).hasSize(1);
        assertThat(mpi.get(0).get("labNumber").asInt()).isEqualTo(9);
        assertThat(mpi.get(0).get("title").asText()).isEqualTo("MPI Collectives");
    }

    @Test
    @DisplayName("a collective starts only the rmi service; with clocksync's eager clock service, every bound port is in 28660-28699")
    void onlyRmiStarts() throws Exception {
        run("{\"rootNode\":1,\"operation\":\"BROADCAST\",\"count\":10}");

        for (ClusterNode node : cluster.nodes()) {
            assertThat(node.runningServices()).as("node %d", node.id()).containsExactlyInAnyOrder("clock", "rmi");
            assertThat(node.ports().rmi()).isBetween(28661, 28665);
            assertThat(node.ports().clock()).isBetween(28671, 28675);
        }
    }

    @Test
    @DisplayName("Prometheus: the four mpi meters with node_id = the root")
    void prometheus() throws Exception {
        run("{\"rootNode\":1,\"operation\":\"BROADCAST\",\"count\":10}");

        String scrape = mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(scrape.lines().filter(l -> l.startsWith("distributed_mpi_collectives_total{"))).anySatisfy(l ->
                assertThat(l).contains("node_id=\"1\"").contains("operation=\"BROADCAST\"").contains("outcome=\"COMPLETE\""));
        assertThat(scrape.lines().filter(l -> l.startsWith("distributed_mpi_messages_total{"))).anySatisfy(l ->
                assertThat(l).contains("node_id=\"1\"").contains("target_node=\"5\"").contains("status=\"DELIVERED\""));
        assertThat(scrape.lines().filter(l -> l.startsWith("distributed_mpi_transfer_latency_seconds_count{")))
                .anySatisfy(l -> assertThat(l).contains("node_id=\"1\"").contains("target_node=\"2\""));
        assertThat(scrape.lines().filter(l -> l.startsWith("distributed_mpi_collective_duration_seconds_count{")))
                .anySatisfy(l -> assertThat(l).contains("node_id=\"1\""));
    }
}
