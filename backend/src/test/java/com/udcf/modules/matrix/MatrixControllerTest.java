package com.udcf.modules.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.multithreading.RequestsNodeService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The /api/modules/matrix contract the E10d page relies on: every DTO's exact JSON field names (a rename
 * breaks a test), nulls instead of invented zeros, every outcome as 200, the ProblemDetail bodies for 400, 404
 * and 409, the module in GET /api/modules, the meters as Prometheus scrapes them, and on every response no
 * number above 2^53 - 1 and no derived seed. Real RMI and real Experiment 2 executors on 127.0.0.1.
 *
 * <p>Crashes nodes, so it has its own context, with a three-node cluster and Jai's test ports (track file,
 * E10c port table): RMI base 28708 (TCP 28709-28711), requests base 28721 (TCP 28722-28724, started by the
 * matrix's prepare step), clock base 28738 (UDP 28739-28741, started by the Experiment 3 module when the context
 * starts). The election, replication and mapreduce bases are 28610, 28620 and 28630, numbers only, never bound
 * (checked by {@link #onlyExpectedServicesStart}). Each test starts with a cluster reset. At class end RMI and the
 * request listeners are stopped and their ports checked free; the context is then closed.</p>
 */
@SpringBootTest(properties = {"udcf.cluster.size=3", "udcf.cluster.ports.rmi-base=28708",
        "udcf.cluster.ports.requests-base=28721", "udcf.cluster.ports.clock-base=28738",
        "udcf.cluster.ports.election-base=28610", "udcf.cluster.ports.replication-base=28620",
        "udcf.cluster.ports.mapreduce-base=28630"})
@AutoConfigureMockMvc
@AutoConfigureObservability
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MatrixControllerTest {

    private static final String BASE = "/api/modules/matrix";
    private static final int RMI_BASE = 28708;
    private static final int REQUESTS_BASE = 28721;
    /** Seeds this class sends or lets default to a run id; none of their derived seeds may appear. */
    private static final List<Long> USER_SEEDS = List.of(42L, 1L, 2L, 3L, 4L, 5L);

    private static final String[] OVERVIEW_FIELDS = {"status", "actionInProgress", "boundName", "limits", "participants",
            "defaultRootNodeId", "defaultRootSource", "minimumSizeNow", "processors", "speedupCeiling", "speedupBasis",
            "algorithmNote", "phaseTimeLabel", "payloadGenerationNote", "fairnessNote", "otherActivityNote",
            "experimentTwoSideEffect", "firstRunNote", "collectiveIdsNote", "collectiveLatencyBasis", "computeRoundTripBasis",
            "gatherLatencyBasis", "payloadSizeBasis", "seriesRule", "glossary", "coHosting", "latest"};
    private static final String[] LIMIT_FIELDS = {"minSize", "maxSize", "seriesSizes", "historySize", "maxSeed",
            "warmupProducts", "computeTimeoutMillis", "resultStoreSize", "runDeadlineMillis", "connectTimeoutMillis",
            "callTimeoutMillis", "transferWorstMillis", "computeWaitMillis", "productAllowanceMillis", "worstCaseRunMillis",
            "recommendedMinClientTimeoutMillis"};
    private static final String[] NODE_FIELDS = {"nodeId", "nodeStatus", "rmiState", "registryPort", "startFailure",
            "matrixEndpointExported", "leader", "keptRunIds", "storedCollectiveIds", "requestListenerRunning", "requestsPort"};
    private static final String[] GLOSSARY_FIELDS = {"id", "label", "kind", "definition"};
    private static final String[] RUN_FIELDS = {"runId", "startedAt", "size", "seed", "rootNodeId", "rootSource", "rootRank",
            "rankNodeIds", "skippedNodeIds", "outcome", "refusal", "deadlineMillis", "scatter", "broadcast", "computes",
            "gather", "timing", "verification", "inputsDelivered", "speedupBasis", "computeRoundTripBasis",
            "gatherLatencyBasis", "payloadSizeBasis"};
    private static final String[] PHASE_FIELDS = {"collectiveId", "operation", "outcome", "refusal", "inputChecksum",
            "totalNanos", "deadlineMillis", "transfers", "verification", "payloadSizeBasis", "latencyBasis"};
    private static final String[] COMPUTE_FIELDS = {"rank", "nodeId", "rowStart", "rowCount", "local", "status",
            "roundTripNanos", "timeToFailureNanos", "computeNanos", "checksum", "failureStage", "failureKind",
            "failureDescription", "failureClass", "failureMessage", "failureCauseClass", "nodeReason", "nodeMessage",
            "senderLamportAtSend", "replyLamport", "lamportAfterReply", "notAttemptedReason"};
    private static final String[] TRANSFER_FIELDS = {"rank", "fromNodeId", "toNodeId", "direction", "status", "offset",
            "elementCount", "latencyNanos", "timeToFailureNanos", "requestBytes", "replyBytes", "expectedChecksum",
            "reportedChecksum", "verified", "failureStage", "failureKind", "failureDescription", "failureClass",
            "failureMessage", "failureCauseClass", "senderLamportAtSend", "replyLamport", "lamportAfterReply",
            "notAttemptedReason"};
    private static final String[] TIMING_FIELDS = {"warmupProducts", "measured", "derived"};
    private static final String[] MEASURED_FIELDS = {"sequentialNanos", "payloadGenerationNanos", "scatterNanos",
            "broadcastNanos", "computePhaseNanos", "gatherNanos", "parallelTotalNanos"};
    private static final String[] DERIVED_FIELDS = {"communicationNanos", "otherNanos", "communicationShare", "speedup",
            "computeBoundSpeedup", "speedupCeiling", "multiplyAdds", "speedupWithheldReason"};
    private static final String[] VERIFICATION_FIELDS = {"rowsExpected", "rowsReceived", "blocksMissing",
            "blocksWithBadChecksum", "elementsCompared", "mismatchedElements", "parallelChecksum", "sequentialChecksum",
            "checksumsMatch", "passed"};
    private static final String[] SERIES_FIELDS = {"points", "gaps", "sizesWithSpeedup", "sizesRequired", "done",
            "seriesSizes", "ranksDiffer", "ranksNote", "basis", "rule"};
    private static final String[] POINT_FIELDS = {"size", "sourceRunId", "sourceOutcome", "ranks", "sequentialNanos",
            "parallelTotalNanos", "communicationNanos", "computePhaseNanos", "speedup"};
    private static final String[] GAP_FIELDS = {"size", "newestRunId", "newestOutcome"};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MatrixModule module;

    @Autowired
    private RmiModule rmiModule;

    @Autowired
    private Cluster cluster;

    @BeforeEach
    void cleanStart() throws Exception {
        await().atMost(60, TimeUnit.SECONDS).until(() -> module.status() != ModuleStatus.BUSY);
        mockMvc.perform(post("/api/cluster/reset")).andExpect(status().isNoContent());
    }

    @AfterAll
    void stopEverything() {
        rmiModule.reset();
        cluster.nodes().forEach(node -> RequestsNodeService.find(node).ifPresent(RequestsNodeService::stop));
        for (int k = 1; k <= cluster.size(); k++) {
            int rmiPort = RMI_BASE + k;
            int requestsPort = REQUESTS_BASE + k;
            await().atMost(5, TimeUnit.SECONDS).until(() -> !RmiTestSupport.acceptThreadAlive(rmiPort)
                    && RmiTestSupport.canBind(rmiPort) && RmiTestSupport.canBind(requestsPort));
        }
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode json(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("NaN").doesNotContain("Infinity");
        MatrixJsonChecks.assertNoDerivedSeed(body, USER_SEEDS);
        JsonNode node = objectMapper.readTree(body);
        MatrixJsonChecks.assertSafeNumbers(node);
        return node;
    }

    private JsonNode overview() throws Exception {
        return json(mockMvc.perform(get(BASE)).andExpect(status().isOk()));
    }

    private JsonNode start() throws Exception {
        return json(mockMvc.perform(post(BASE + "/start")).andExpect(status().isOk()));
    }

    private ResultActions postRun(String body) throws Exception {
        return mockMvc.perform(post(BASE + "/runs").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode run(String body) throws Exception {
        return json(postRun(body).andExpect(status().isOk()));
    }

    private static void assertFields(JsonNode node, String... names) {
        Set<String> actual = new TreeSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).as("fields of %s", node).isEqualTo(new TreeSet<>(List.of(names)));
    }

    private static JsonNode node(JsonNode overview, int nodeId) {
        for (JsonNode participant : overview.get("participants")) {
            if (participant.get("nodeId").asInt() == nodeId) {
                return participant;
            }
        }
        throw new AssertionError("no node " + nodeId);
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asLong()));
        return values;
    }

    // ------------------------------------------------------------------ registration and overview

    @Test
    @DisplayName("GET /api/modules lists the matrix module as lab 10")
    void listedAsLab10() throws Exception {
        JsonNode modules = objectMapper.readTree(mockMvc.perform(get("/api/modules")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode matrix = null;
        for (JsonNode m : modules) {
            if (m.get("id").asText().equals("matrix")) {
                matrix = m;
            }
        }
        assertThat(matrix).isNotNull();
        assertThat(matrix.get("labNumber").asInt()).isEqualTo(10);
        assertThat(matrix.get("title").asText()).isEqualTo("Parallel Matrix Multiplication");
    }

    @Test
    @DisplayName("overview after a reset: exact fields, IDLE, RMI unknowns are null, limits and texts present")
    void overviewFields() throws Exception {
        JsonNode overview = overview();

        assertFields(overview, OVERVIEW_FIELDS);
        assertFields(overview.get("limits"), LIMIT_FIELDS);
        assertFields(overview.get("glossary").get(0), GLOSSARY_FIELDS);
        assertThat(overview.get("status").asText()).isEqualTo("IDLE");
        assertThat(overview.get("participants")).hasSize(3);
        for (JsonNode node : overview.get("participants")) {
            assertFields(node, NODE_FIELDS);
            assertThat(node.get("matrixEndpointExported").asBoolean()).isFalse();
            assertThat(node.get("keptRunIds").isNull()).isTrue();
            assertThat(node.get("storedCollectiveIds").isNull()).isTrue();
            assertThat(node.get("requestListenerRunning").isBoolean()).isTrue();
            assertThat(node.get("requestsPort").asInt()).isEqualTo(REQUESTS_BASE + node.get("nodeId").asInt());
        }
        assertThat(overview.get("latest").isNull()).isTrue();
        assertThat(overview.get("limits").get("maxSize").asInt()).isEqualTo(316);
        assertThat(overview.get("limits").get("worstCaseRunMillis").asLong()).isEqualTo(26_001);
        assertThat(overview.get("limits").get("recommendedMinClientTimeoutMillis").asLong()).isEqualTo(26_001);
        assertThat(overview.get("minimumSizeNow").asInt()).isEqualTo(3);
        assertThat(overview.get("experimentTwoSideEffect").asText()).contains("(28722 to 28724 here)");
        assertThat(overview.get("coHosting").get("simulated").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("POST /start: RUNNING; every node exports MatrixEndpoint, keeps nothing yet, and has its request listener")
    void start_exportsAndPrepares() throws Exception {
        JsonNode overview = start();

        assertThat(overview.get("status").asText()).isEqualTo("RUNNING");
        for (JsonNode node : overview.get("participants")) {
            assertThat(node.get("rmiState").asText()).isEqualTo("EXPORTED");
            assertThat(node.get("matrixEndpointExported").asBoolean()).isTrue();
            assertThat(longs(node.get("keptRunIds"))).isEmpty();
            assertThat(node.get("storedCollectiveIds").isArray()).isTrue();
            assertThat(node.get("requestListenerRunning").asBoolean()).isTrue();
        }
        JsonNode events = json(mockMvc.perform(get("/api/events").param("module", "matrix")).andExpect(status().isOk()));
        long exported = 0;
        for (JsonNode event : events) {
            if (event.get("type").asText().equals("MATRIX_ENDPOINT_EXPORTED")) {
                exported++;
            }
        }
        assertThat(exported).isEqualTo(3);
        assertThat(start().get("status").asText()).isEqualTo("RUNNING");   // idempotent
    }

    // ------------------------------------------------------------------ runs

    @Test
    @DisplayName("COMPLETE run: exact fields of every DTO, exact equality with the sequential product, nulls where nothing applies")
    void completeRun() throws Exception {
        JsonNode run = run("{\"size\":30,\"seed\":42}");

        assertFields(run, RUN_FIELDS);
        assertFields(run.get("scatter"), PHASE_FIELDS);
        assertFields(run.get("broadcast"), PHASE_FIELDS);
        assertFields(run.get("scatter").get("transfers").get(1), TRANSFER_FIELDS);
        assertFields(run.get("computes").get(0), COMPUTE_FIELDS);
        assertFields(run.get("gather").get(0), TRANSFER_FIELDS);
        assertFields(run.get("timing"), TIMING_FIELDS);
        assertFields(run.get("timing").get("measured"), MEASURED_FIELDS);
        assertFields(run.get("timing").get("derived"), DERIVED_FIELDS);
        assertFields(run.get("verification"), VERIFICATION_FIELDS);

        assertThat(run.get("outcome").asText()).isEqualTo("COMPLETE");
        assertThat(run.get("seed").asLong()).isEqualTo(42);
        assertThat(run.get("rankNodeIds")).hasSize(3);
        JsonNode v = run.get("verification");
        assertThat(v.get("passed").asBoolean()).isTrue();
        assertThat(v.get("mismatchedElements").asLong()).isZero();
        assertThat(v.get("elementsCompared").asLong()).isEqualTo(900);
        assertThat(v.get("parallelChecksum").asText()).isEqualTo(v.get("sequentialChecksum").asText());
        assertThat(run.get("timing").get("derived").get("speedup").isNumber()).isTrue();
        assertThat(run.get("timing").get("derived").get("speedupWithheldReason").isNull()).isTrue();
        assertThat(run.get("timing").get("derived").get("multiplyAdds").asLong()).isEqualTo(27_000);
        JsonNode rootCompute = run.get("computes").get(0);
        assertThat(rootCompute.get("local").asBoolean()).isTrue();
        assertThat(rootCompute.get("roundTripNanos").isNull()).isTrue();
        assertThat(rootCompute.get("senderLamportAtSend").isNull()).isTrue();
        assertThat(run.get("computes").get(1).get("computeNanos").isNumber()).isTrue();
        assertThat(run.get("computes").get(1).get("roundTripNanos").isNumber()).isTrue();
        assertThat(run.get("gather").get(0).get("status").asText()).isEqualTo("LOCAL");
        assertThat(run.get("gather").get(0).get("latencyNanos").isNull()).isTrue();
        assertThat(run.get("scatter").get("collectiveId").asLong()).isEqualTo(1_000_000_002L);

        JsonNode overview = overview();
        assertThat(longs(node(overview, 1).get("keptRunIds"))).isEmpty();   // the root computes its own block locally
        assertThat(longs(node(overview, 2).get("keptRunIds"))).containsExactly(1L);
        assertThat(longs(node(overview, 2).get("storedCollectiveIds"))).contains(1_000_000_002L, 1_000_000_003L);
        assertThat(overview.get("latest").get("runId").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("REFUSED (size 2 over three live nodes) is a 200 with the reason and no run-related figure")
    void refused() throws Exception {
        JsonNode run = run("{\"size\":2,\"seed\":42}");

        assertThat(run.get("outcome").asText()).isEqualTo("REFUSED");
        assertThat(run.get("refusal").get("reason").asText()).isEqualTo("SIZE_BELOW_PARTICIPANTS");
        for (String field : new String[] {"rootRank", "scatter", "broadcast", "timing", "verification", "inputsDelivered",
                "speedupBasis", "computeRoundTripBasis", "gatherLatencyBasis", "payloadSizeBasis"}) {
            assertThat(run.get(field).isNull()).as(field).isTrue();
        }
        assertThat(run.get("computes")).isEmpty();
        assertThat(run.get("gather")).isEmpty();
    }

    @Test
    @DisplayName("a node crashed before the run is skipped; the run completes over the others")
    void crashedNodeSkipped() throws Exception {
        mockMvc.perform(post("/api/cluster/nodes/3/crash")).andExpect(status().isOk());
        try {
            JsonNode run = run("{\"size\":30,\"seed\":42}");
            assertThat(run.get("outcome").asText()).isEqualTo("COMPLETE");
            assertThat(longs(run.get("skippedNodeIds"))).containsExactly(3L);
            assertThat(longs(run.get("rankNodeIds"))).containsExactly(1L, 2L);
        } finally {
            mockMvc.perform(post("/api/cluster/nodes/3/recover")).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("history newest first, latest, by id; series shape after one run of a series size")
    void historyAndSeries() throws Exception {
        run("{\"size\":50,\"seed\":42}");
        run("{\"size\":30}");

        JsonNode runs = json(mockMvc.perform(get(BASE + "/runs")).andExpect(status().isOk()));
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0).get("runId").asLong()).isEqualTo(2);
        assertThat(runs.get(0).get("seed").asLong()).isEqualTo(2);   // left out, so the run id
        assertThat(json(mockMvc.perform(get(BASE + "/runs/latest")).andExpect(status().isOk())).get("runId").asLong())
                .isEqualTo(2);
        assertThat(json(mockMvc.perform(get(BASE + "/runs/1")).andExpect(status().isOk())).get("size").asInt()).isEqualTo(50);

        JsonNode series = json(mockMvc.perform(get(BASE + "/series")).andExpect(status().isOk()));
        assertFields(series, SERIES_FIELDS);
        assertThat(series.get("points")).hasSize(1);
        assertFields(series.get("points").get(0), POINT_FIELDS);
        assertThat(series.get("points").get(0).get("sourceRunId").asLong()).isEqualTo(1);
        assertThat(series.get("points").get(0).get("speedup").isNumber()).isTrue();
        assertThat(series.get("gaps")).hasSize(3);
        assertFields(series.get("gaps").get(0), GAP_FIELDS);
        assertThat(series.get("gaps").get(0).get("newestRunId").isNull()).isTrue();
        assertThat(series.get("done").asBoolean()).isFalse();
        assertThat(series.get("sizesRequired").asInt()).isEqualTo(3);
        assertThat(series.get("basis").asText()).isNotBlank();
    }

    // ------------------------------------------------------------------ errors

    @Test
    @DisplayName("400 Invalid request parameters with every field; Spring's default 400 for an unreadable body or a bad id")
    void badRequests() throws Exception {
        JsonNode missing = json(postRun("{\"seed\":-1}").andExpect(status().isBadRequest()));
        assertThat(missing.get("title").asText()).isEqualTo("Invalid request parameters");
        assertThat(missing.get("errors").get("size").asText()).isEqualTo("size is required");
        assertThat(missing.get("errors").get("seed").asText()).isEqualTo("seed must be 0-9007199254740991, was -1");
        assertThat(json(postRun("{\"size\":317}").andExpect(status().isBadRequest())).get("errors").get("size").asText())
                .isEqualTo("size must be 2-316, was 317");
        assertThat(json(postRun("{\"size\":1}").andExpect(status().isBadRequest())).get("errors").get("size").asText())
                .isEqualTo("size must be 2-316, was 1");

        JsonNode unreadable = json(postRun("{\"size\":\"abc\"}").andExpect(status().isBadRequest()));
        assertThat(unreadable.has("errors")).isFalse();
        JsonNode badId = json(mockMvc.perform(get(BASE + "/runs/abc")).andExpect(status().isBadRequest()));
        assertThat(badId.has("errors")).isFalse();
        assertThat(module.history()).isEmpty();
    }

    @Test
    @DisplayName("404: no run yet (runId null), unknown run id, unknown root node")
    void notFound() throws Exception {
        JsonNode none = json(mockMvc.perform(get(BASE + "/runs/latest")).andExpect(status().isNotFound()));
        assertThat(none.get("title").asText()).isEqualTo("Unknown run");
        assertThat(none.get("runId").isNull()).isTrue();
        JsonNode unknown = json(mockMvc.perform(get(BASE + "/runs/999")).andExpect(status().isNotFound()));
        assertThat(unknown.get("runId").asLong()).isEqualTo(999);
        JsonNode node = json(postRun("{\"size\":30,\"rootNode\":9}").andExpect(status().isNotFound()));
        assertThat(node.get("title").asText()).isEqualTo("Unknown node");
        assertThat(node.get("nodeId").asInt()).isEqualTo(9);
    }

    @Test
    @DisplayName("409: requested root down (Node down), every node down (Node state conflict, node 0), module busy")
    void conflicts() throws Exception {
        mockMvc.perform(post("/api/cluster/nodes/2/crash")).andExpect(status().isOk());
        JsonNode down = json(postRun("{\"size\":30,\"rootNode\":2}").andExpect(status().isConflict()));
        assertThat(down.get("title").asText()).isEqualTo("Node down");
        assertThat(down.get("nodeId").asInt()).isEqualTo(2);

        mockMvc.perform(post("/api/cluster/nodes/1/crash")).andExpect(status().isOk());
        mockMvc.perform(post("/api/cluster/nodes/3/crash")).andExpect(status().isOk());
        JsonNode none = json(postRun("{\"size\":30}").andExpect(status().isConflict()));
        assertThat(none.get("title").asText()).isEqualTo("Node state conflict");
        assertThat(none.get("nodeId").asInt()).isZero();
        mockMvc.perform(post("/api/cluster/reset")).andExpect(status().isNoContent());

        ModuleActionGuard.ActionTicket held = module.guard().begin("Matrix run of size 316");
        try {
            JsonNode busy = json(postRun("{\"size\":30}").andExpect(status().isConflict()));
            assertThat(busy.get("title").asText()).isEqualTo("Module busy");
            assertThat(busy.get("moduleId").asText()).isEqualTo("matrix");
            assertThat(busy.get("actionInProgress").asText()).isEqualTo("Matrix run of size 316");
            mockMvc.perform(post(BASE + "/start")).andExpect(status().isConflict());
        } finally {
            held.close();
        }
        assertThat(module.history()).isEmpty();
    }

    // ------------------------------------------------------------------ Experiment 2 listeners (ruling 4)

    @Test
    @DisplayName("a cluster reset does not stop the request listeners the matrix started (RMI stops; the listeners keep running)")
    void resetKeepsListeners() throws Exception {
        start();

        mockMvc.perform(post("/api/cluster/reset")).andExpect(status().isNoContent());

        for (ClusterNode node : cluster.nodes()) {
            assertThat(RequestsNodeService.find(node)).as("node %d", node.id()).hasValueSatisfying(
                    service -> assertThat(service.isRunning()).isTrue());
        }
        JsonNode overview = overview();
        for (JsonNode node : overview.get("participants")) {
            assertThat(node.get("requestListenerRunning").asBoolean()).isTrue();
            assertThat(node.get("matrixEndpointExported").asBoolean()).isFalse();
        }
    }

    @Test
    @DisplayName("a crash closes that node's listener (the others keep theirs); recovering the node reopens it")
    void crashClosesListener() throws Exception {
        start();

        mockMvc.perform(post("/api/cluster/nodes/2/crash")).andExpect(status().isOk());
        JsonNode crashed = overview();
        assertThat(node(crashed, 2).get("requestListenerRunning").asBoolean()).isFalse();
        assertThat(node(crashed, 1).get("requestListenerRunning").asBoolean()).isTrue();
        assertThat(node(crashed, 3).get("requestListenerRunning").asBoolean()).isTrue();

        mockMvc.perform(post("/api/cluster/nodes/2/recover")).andExpect(status().isOk());
        assertThat(node(overview(), 2).get("requestListenerRunning").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Experiment 2's own batches still work on a listener the matrix started (no 'already running' failure)")
    void experimentTwoStillWorks() throws Exception {
        start();
        run("{\"size\":30,\"seed\":42}");

        JsonNode batch = objectMapper.readTree(mockMvc.perform(post("/api/modules/multithreading/nodes/2/batches")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"count\":3,\"type\":\"CPU_HASH\",\"payloadSize\":10}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        assertThat(batch.get("requested").asInt()).isEqualTo(3);
        assertThat(batch.get("rejected").asInt()).isZero();
        await().atMost(30, TimeUnit.SECONDS).until(() -> {
            JsonNode requests = objectMapper.readTree(mockMvc.perform(get("/api/modules/multithreading/nodes/2/requests"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            int completed = 0;
            for (JsonNode request : requests) {
                if (request.get("status").asText().equals("COMPLETED")) {
                    completed++;
                }
            }
            return completed == 3;   // only Experiment 2's own requests: the matrix block is not in this history
        });
    }

    // ------------------------------------------------------------------ services, ports, meters

    @Test
    @DisplayName("only clock, rmi and requests run, on this class's ports; election, replication and mapreduce never start")
    void onlyExpectedServicesStart() throws Exception {
        run("{\"size\":30,\"seed\":42}");

        for (ClusterNode node : cluster.nodes()) {
            assertThat(node.runningServices()).as("node %d", node.id()).containsExactlyInAnyOrder("clock", "rmi", "requests");
            assertThat(node.ports().rmi()).isBetween(28709, 28711);
            assertThat(node.ports().requests()).isBetween(28722, 28724);
            assertThat(node.ports().clock()).isBetween(28739, 28741);
        }
    }

    @Test
    @DisplayName("Prometheus: the four matrix meters, node_id = the root (compute: the computing node)")
    void prometheus() throws Exception {
        run("{\"size\":30,\"seed\":42}");

        String scrape = mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(scrape.lines().filter(l -> l.startsWith("distributed_matrix_runs_total{"))).anySatisfy(l ->
                assertThat(l).contains("node_id=\"1\"").contains("outcome=\"COMPLETE\""));
        assertThat(scrape.lines().filter(l -> l.startsWith("distributed_matrix_execution_duration_seconds_count{")))
                .anySatisfy(l -> assertThat(l).contains("node_id=\"1\"").contains("outcome=\"COMPLETE\""));
        assertThat(scrape.lines().filter(l -> l.startsWith("distributed_matrix_compute_duration_seconds_count{")))
                .anySatisfy(l -> assertThat(l).contains("node_id=\"3\""));
        assertThat(scrape.lines().filter(l -> l.startsWith("distributed_matrix_communication_duration_seconds_count{")))
                .anySatisfy(l -> assertThat(l).contains("node_id=\"1\"").contains("phase=\"GATHER\""));
    }
}
