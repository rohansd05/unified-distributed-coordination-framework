package com.udcf.modules.election;

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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Experiment 4's REST API against the real module and cluster (MockMvc, own Spring context,
 * closed afterwards, because these tests crash nodes and elect leaders).
 *
 * <p>Every port base is overridden into the Track B block (below 32768, outside the Linux and
 * Windows ephemeral ranges), so the context never binds a default port: RMI 26400, clock 26410,
 * election 26420, replication 26430, requests 26440, MapReduce 26450. Only the election service
 * starts here, on 26421 to 26425. Election timings are short.</p>
 */
@SpringBootTest(properties = {
        "udcf.cluster.ports.rmi-base=26400",
        "udcf.cluster.ports.clock-base=26410",
        "udcf.cluster.ports.election-base=26420",
        "udcf.cluster.ports.replication-base=26430",
        "udcf.cluster.ports.requests-base=26440",
        "udcf.cluster.ports.mapreduce-base=26450",
        "udcf.election.ok-timeout-millis=400",
        "udcf.election.coordinator-timeout-millis=1500",
        "udcf.election.probe-timeout-millis=150",
        "udcf.election.ring-completion-timeout-millis=3000",
        "udcf.election.heartbeat-interval-millis=100",
        "udcf.election.heartbeat-timeout-millis=1500",
        "udcf.election.round-timeout-millis=10000"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ElectionControllerTest {

    private static final String BASE = "/api/modules/election";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Cluster cluster;

    @Autowired
    private ElectionModule module;

    @BeforeEach
    void setUp() {
        cleanSlate();
    }

    @AfterEach
    void tearDown() {
        cleanSlate();
    }

    private void cleanSlate() {
        await().atMost(Duration.ofSeconds(10)).until(() -> module.status() != ModuleStatus.BUSY);
        cluster.recoverAll();
        module.reset();
    }

    private JsonNode getJson(String url) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get(url)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions postStart(String body) throws Exception {
        return mockMvc.perform(post(BASE + "/elections").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("GET overview: no leader, five nodes on 26421 to 26425, the configured timings")
    void overviewWithoutLeader() throws Exception {
        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaderId").value(nullValue()))
                .andExpect(jsonPath("$.nodes", hasSize(5)))
                .andExpect(jsonPath("$.nodes[*].port", contains(26421, 26422, 26423, 26424, 26425)))
                .andExpect(jsonPath("$.nodes[0].electionsWon").isNumber())
                .andExpect(jsonPath("$.nodes[0].roundsTimed").isNumber())
                .andExpect(jsonPath("$.nodes[0]", hasKey("meanDurationMillis")))
                .andExpect(jsonPath("$.consensus.passed").value(false))
                .andExpect(jsonPath("$.currentRound").value(nullValue()))
                .andExpect(jsonPath("$.settings.okTimeoutMillis").value(400))
                .andExpect(jsonPath("$.settings.heartbeatTimeoutMillis").value(1500))
                .andExpect(jsonPath("$.settings.roundTimeoutMillis").value(10000));
    }

    @Test
    @DisplayName("POST a Bully election answers 202 with the open round; node 5 then shows LEADER in GET /api/cluster")
    void postStartsBullyReturns202ThenLeaderAppearsInClusterApi() throws Exception {
        postStart("{\"algorithm\":\"BULLY\",\"nodeId\":1}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.algorithm").value("BULLY"))
                .andExpect(jsonPath("$.trigger").value("MANUAL"))
                .andExpect(jsonPath("$.initiatorNodeId").value(1))
                .andExpect(jsonPath("$.outcome").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.leaderId").value(nullValue()))
                .andExpect(jsonPath("$.durationMillis").value(nullValue()));

        await().atMost(Duration.ofSeconds(10)).until(() -> {
            JsonNode overview = getJson(BASE);
            return overview.path("leaderId").asInt() == 5 && "ELECTED".equals(overview.path("lastRound").path("outcome").asText());
        });
        JsonNode clusterJson = getJson("/api/cluster");
        assertThat(clusterJson.path("nodes").get(4).path("roles").toString()).isEqualTo("[\"LEADER\"]");
        assertThat(clusterJson.path("nodes").get(0).path("roles").toString()).isEqualTo("[]");
        assertThat(getJson(BASE).path("lastRound").path("durationMillis").asDouble()).isPositive();
    }

    @Test
    @DisplayName("a missing or unknown algorithm or a node id below 1 is 400")
    void invalidCommandIs400() throws Exception {
        postStart("{}").andExpect(status().isBadRequest());
        postStart("{\"algorithm\":\"PAXOS\",\"nodeId\":1}").andExpect(status().isBadRequest());
        postStart("{\"algorithm\":\"BULLY\",\"nodeId\":0}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unknown node is 404")
    void unknownNodeIs404() throws Exception {
        postStart("{\"algorithm\":\"RING\",\"nodeId\":9}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.nodeId").value(9));
    }

    @Test
    @DisplayName("a crashed node is 409 Node down")
    void crashedNodeIs409() throws Exception {
        mockMvc.perform(post("/api/cluster/nodes/2/crash")).andExpect(status().isOk());
        postStart("{\"algorithm\":\"BULLY\",\"nodeId\":2}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Node down"))
                .andExpect(jsonPath("$.nodeId").value(2));
    }

    @Test
    @DisplayName("a start while another action holds the module is 409 Module busy")
    void busyIs409() throws Exception {
        try (ModuleActionGuard.ActionTicket ignored = module.guard().begin("test action")) {
            postStart("{\"algorithm\":\"BULLY\",\"nodeId\":1}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Module busy"))
                    .andExpect(jsonPath("$.moduleId").value("election"));
        }
    }

    @Test
    @DisplayName("GET consensus has reached, coordinatorId, coordinatorAlive, passed and disagreeingNodes")
    void consensusEndpointShape() throws Exception {
        mockMvc.perform(get(BASE + "/consensus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reached").isBoolean())
                .andExpect(jsonPath("$.coordinatorId").value(nullValue()))
                .andExpect(jsonPath("$.coordinatorAlive").value(false))
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.disagreeingNodes").isArray());
    }

    @Test
    @DisplayName("GET /api/modules lists election as lab 4, Bully and Ring Election")
    void moduleListedAsLab4() throws Exception {
        mockMvc.perform(get("/api/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'election')].labNumber").value(4))
                .andExpect(jsonPath("$[?(@.id == 'election')].title").value("Bully and Ring Election"));
    }
}
