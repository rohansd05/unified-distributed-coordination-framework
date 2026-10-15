package com.udcf.modules.faulttolerance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udcf.core.cluster.ClusterResetService;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.module.ModuleActionGuard;
import com.udcf.core.module.ModuleStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Experiment 8's REST API against the real module and cluster (MockMvc, own Spring context,
 * closed afterwards, because these tests crash nodes and reset the cluster).
 *
 * <p>Every port base is overridden into Track B's range 26500-26555: RMI 26500, clock 26510,
 * election 26520, replication 26530, requests 26540, MapReduce 26550. Only the election
 * (26521-26525) and replication (26531-26535) services start here; the rest are lazy and never
 * started. Timings are short (as FailoverFixture); the update budget is 6.</p>
 */
@SpringBootTest(properties = {
        "udcf.cluster.ports.rmi-base=26500",
        "udcf.cluster.ports.clock-base=26510",
        "udcf.cluster.ports.election-base=26520",
        "udcf.cluster.ports.replication-base=26530",
        "udcf.cluster.ports.requests-base=26540",
        "udcf.cluster.ports.mapreduce-base=26550",
        "udcf.election.ok-timeout-millis=400",
        "udcf.election.coordinator-timeout-millis=1500",
        "udcf.election.probe-timeout-millis=150",
        "udcf.election.ring-completion-timeout-millis=3000",
        "udcf.election.heartbeat-interval-millis=100",
        "udcf.election.heartbeat-timeout-millis=600",
        "udcf.election.round-timeout-millis=10000",
        "udcf.replication.async-delay-millis=400",
        "udcf.replication.timeout-millis=300",
        "udcf.faulttolerance.client.max-attempts=40",
        "udcf.faulttolerance.client.retry-delay-millis=100",
        "udcf.faulttolerance.role-query.retry-delay-millis=300",
        "udcf.faulttolerance.role-query.max-attempts=3",
        "udcf.faulttolerance.promotion.catch-up-timeout-millis=250",
        "udcf.faulttolerance.stream.max-rate-per-second=200",
        "udcf.faulttolerance.stream.max-updates=6"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FaultToleranceControllerTest {

    private static final String BASE = "/api/modules/faulttolerance";
    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FaultToleranceModule module;

    @Autowired
    private ClusterResetService resetService;

    @Autowired
    private ClusterEventBus bus;

    @BeforeEach
    void setUp() {
        cleanSlate();
    }

    @AfterEach
    void tearDown() {
        cleanSlate();
    }

    private void cleanSlate() {
        await().atMost(WAIT).until(() -> module.status() != ModuleStatus.BUSY);
        resetService.reset();
    }

    private JsonNode getJson(String url) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void awaitServingPrimary() {
        await().atMost(WAIT).until(() -> getJson(BASE + "/status").path("currentPrimaryId").asInt() == 1
                && module.currentState().nodes().stream().allMatch(n -> "READY".equals(n.rejoin())));
    }

    @Test
    @DisplayName("GET overview before start: IDLE, nulls while unknown, settings, glossary and notes; it starts nothing")
    void overviewBeforeStart() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.status").value("IDLE"))
                .andExpect(jsonPath("$.current.started").value(false))
                .andExpect(jsonPath("$.current.currentPrimaryId").value(nullValue()))
                .andExpect(jsonPath("$.current.primaryEpoch").value(nullValue()))
                .andExpect(jsonPath("$.current.dataLossNow").value(nullValue()))
                .andExpect(jsonPath("$.current.nodes", hasSize(5)))
                // after a cluster reset the services still run (BACKUP at epoch 1); before any start they are null
                .andExpect(jsonPath("$.current.nodes[?(@.role == 'PRIMARY')]", hasSize(0)))
                .andExpect(jsonPath("$.current.stream.state").value("STOPPED"))
                .andExpect(jsonPath("$.current.stream.ratePerSecond").value(nullValue()))
                .andExpect(jsonPath("$.current.stream.maxUpdates").value(6))
                .andExpect(jsonPath("$.current.stream.remainingUpdates").value(6))
                .andExpect(jsonPath("$.current.splitBrain.passed").value(true))
                .andExpect(jsonPath("$.latestRun").value(nullValue()))
                .andExpect(jsonPath("$.settings.maxRatePerSecond").value(200))
                .andExpect(jsonPath("$.settings.asyncDelayMillis").value(400))
                .andExpect(jsonPath("$.settings.asyncDelaySimulated").value(true))
                .andExpect(jsonPath("$.settings.coHostingSimulated").value(true))
                .andExpect(jsonPath("$.settings.worstCaseFailoverMillis").value(2400))
                .andExpect(jsonPath("$.settings.retryWindowMillis").value(4000))
                .andExpect(jsonPath("$.glossary", hasSize(8)))
                .andExpect(jsonPath("$.glossary[0].id").value("detection"))
                .andExpect(jsonPath("$.glossary[6].kind").value("derived"))
                .andExpect(jsonPath("$.notes[1]", containsString("waits 400 ms")));
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
    }

    @Test
    @DisplayName("POST start answers 202 STARTED, then 200 ALREADY_STARTED with a reason")
    void startIsIdempotent() throws Exception {
        mockMvc.perform(post(BASE + "/start")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("STARTED"))
                .andExpect(jsonPath("$.reason").value(nullValue()))
                .andExpect(jsonPath("$.status.started").value(true));
        mockMvc.perform(post(BASE + "/start")).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_STARTED"))
                .andExpect(jsonPath("$.reason", containsString("already running")));
    }

    @Test
    @DisplayName("100 GET /status (and overview, runs) add zero events and leave violationsSeen unchanged")
    void readsPublishNothing() throws Exception {
        mockMvc.perform(post(BASE + "/start")).andExpect(status().isAccepted());
        awaitServingPrimary();
        long before = bus.publishedCount();
        int seen = getJson(BASE + "/status").path("splitBrain").path("violationsSeen").asInt();
        for (int i = 0; i < 100; i++) {
            mockMvc.perform(get(BASE + "/status")).andExpect(status().isOk());
        }
        mockMvc.perform(get(BASE)).andExpect(status().isOk());
        mockMvc.perform(get(BASE + "/runs")).andExpect(status().isOk());
        assertThat(bus.publishedCount()).isEqualTo(before);
        assertThat(getJson(BASE + "/status").path("splitBrain").path("violationsSeen").asInt()).isEqualTo(seen);
    }

    @Test
    @DisplayName("stream start on a never-started module: 202 and the module starts; 409 running; mode 200; stop 200 then 409")
    void streamLifecycle() throws Exception {
        postJson(BASE + "/stream/start", "{\"mode\":\"SYNCHRONOUS\",\"ratePerSecond\":20}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.mode").value("SYNCHRONOUS"))
                .andExpect(jsonPath("$.ratePerSecond").value(20));
        assertThat(getJson(BASE + "/status").path("started").asBoolean()).isTrue();
        postJson(BASE + "/stream/start", "{\"mode\":\"SYNCHRONOUS\",\"ratePerSecond\":20}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("STREAM_RUNNING"))
                .andExpect(jsonPath("$.moduleId").value("faulttolerance"));
        mockMvc.perform(put(BASE + "/mode").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ASYNCHRONOUS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("ASYNCHRONOUS"));
        mockMvc.perform(post(BASE + "/stream/stop")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STOPPED"))
                .andExpect(jsonPath("$.stopReason").value("REQUESTED"));
        mockMvc.perform(post(BASE + "/stream/stop")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Not possible now"))
                .andExpect(jsonPath("$.reason").value("STREAM_NOT_RUNNING"));
    }

    @Test
    @DisplayName("400: missing fields, a rate above the limit, an unknown model, a bad run id")
    void invalidInputIs400() throws Exception {
        postJson(BASE + "/stream/start", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("mode")))
                .andExpect(jsonPath("$.errors", hasKey("ratePerSecond")));
        postJson(BASE + "/stream/start", "{\"mode\":\"SYNCHRONOUS\",\"ratePerSecond\":201}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ratePerSecond").value("must be between 1 and 200"));
        postJson(BASE + "/stream/start", "{\"mode\":\"EVENTUAL\",\"ratePerSecond\":1}")
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(BASE + "/mode").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasKey("mode")));
        mockMvc.perform(get(BASE + "/runs/abc")).andExpect(status().isBadRequest());
        assertThat(module.status()).isEqualTo(ModuleStatus.IDLE);
    }

    @Test
    @DisplayName("404: no run yet (runId null) and an unknown run; 409: crash or recover with nothing to act on")
    void notFoundAndConflicts() throws Exception {
        mockMvc.perform(get(BASE + "/runs/latest")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Unknown run"))
                .andExpect(jsonPath("$.runId").value(nullValue()));
        mockMvc.perform(get(BASE + "/runs/99")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.runId").value(99));
        mockMvc.perform(get(BASE + "/runs")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(post(BASE + "/crash-primary")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("NO_SERVING_PRIMARY"))
                .andExpect(jsonPath("$.detail", containsString("has not started")));
        mockMvc.perform(post(BASE + "/recover")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("NOTHING_TO_RECOVER"));
    }

    @Test
    @DisplayName("409 busy while the guard is held, with moduleId and actionInProgress")
    void busyIs409() throws Exception {
        try (ModuleActionGuard.ActionTicket ignored = module.guard().begin("Test action")) {
            mockMvc.perform(post(BASE + "/start")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Module busy"))
                    .andExpect(jsonPath("$.moduleId").value("faulttolerance"))
                    .andExpect(jsonPath("$.actionInProgress").value("Test action"));
            mockMvc.perform(get(BASE + "/status")).andExpect(jsonPath("$.status").value("BUSY"));
        }
    }

    @Test
    @DisplayName("crash 202, the failover restores the service; runs, latest and by id; recover 202")
    void crashFailoverRecover() throws Exception {
        mockMvc.perform(post(BASE + "/start")).andExpect(status().isAccepted());
        awaitServingPrimary();
        postJson(BASE + "/stream/start", "{\"mode\":\"SYNCHRONOUS\",\"ratePerSecond\":2}").andExpect(status().isAccepted());
        mockMvc.perform(post(BASE + "/crash-primary")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.nodeId").value(1))
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.crashSource").value("ACTION"))
                .andExpect(jsonPath("$.status.dataLossNow").value(nullValue()))
                .andExpect(jsonPath("$.status.nodes[0].role").value(nullValue()));
        await().atMost(WAIT).until(() -> !module.runs().isEmpty() && module.latestRun().dataLoss() != null);
        mockMvc.perform(get(BASE + "/runs/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RESTORED"))
                .andExpect(jsonPath("$.dataLoss.lost").value(0))
                .andExpect(jsonPath("$.milestones[0].kind").value("CRASHED"))
                .andExpect(jsonPath("$.measurements.recoveryMillis").value(nullValue()));
        mockMvc.perform(get(BASE + "/runs/1")).andExpect(status().isOk()).andExpect(jsonPath("$.runId").value(1));
        mockMvc.perform(get(BASE + "/runs")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(post(BASE + "/recover")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.nodeId").value(1))
                .andExpect(jsonPath("$.previousEpoch").value(2));
        mockMvc.perform(post(BASE + "/recover")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("NOTHING_TO_RECOVER"));
    }

    @Test
    @DisplayName("409 UPDATE_LIMIT_REACHED names the cluster reset; a cluster reset gives a fresh budget")
    void budgetIs409UntilReset() throws Exception {
        postJson(BASE + "/stream/start", "{\"mode\":\"SYNCHRONOUS\",\"ratePerSecond\":200}").andExpect(status().isAccepted());
        await().atMost(WAIT).until(() -> "LIMIT_REACHED".equals(
                getJson(BASE + "/status").path("stream").path("stopReason").asText()));
        postJson(BASE + "/stream/start", "{\"mode\":\"SYNCHRONOUS\",\"ratePerSecond\":200}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("UPDATE_LIMIT_REACHED"))
                .andExpect(jsonPath("$.detail", containsString("A cluster reset gives a fresh budget")));
        mockMvc.perform(post("/api/cluster/reset")).andExpect(status().isNoContent());
        mockMvc.perform(get(BASE + "/status")).andExpect(jsonPath("$.stream.remainingUpdates").value(6))
                .andExpect(jsonPath("$.started").value(false));
    }

    @Test
    @DisplayName("GET /api/modules lists faulttolerance as lab 8, Fault Tolerance")
    void listedInModules() throws Exception {
        mockMvc.perform(get("/api/modules")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'faulttolerance')].labNumber").value(8))
                .andExpect(jsonPath("$[?(@.id == 'faulttolerance')].title").value("Fault Tolerance"));
    }
}
