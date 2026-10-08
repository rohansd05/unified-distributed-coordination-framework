package com.udcf.modules.clocksync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udcf.core.cluster.Cluster;
import com.udcf.core.module.ModuleStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end web tests for /api/modules/clocksync endpoints.
 *
 * <p>Uses test-isolated clock ports (24600 base) within the reserved Track D range (24100-24899).</p>
 */
@SpringBootTest(properties = {
        "udcf.cluster.ports.clock-base=24600"
})
@AutoConfigureMockMvc
class ClockSyncControllerTest {

    private static final String BASE = "/api/modules/clocksync";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Cluster cluster;

    @Autowired
    private ClockSyncModule module;

    @AfterEach
    void tearDown() {
        await().atMost(Duration.ofSeconds(5)).until(() -> module.status() == ModuleStatus.IDLE);
        for (int i = 1; i <= cluster.size(); i++) {
            if (!cluster.node(i).isUp()) {
                cluster.node(i).recover();
            }
        }
        module.reset();
    }

    private JsonNode json(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("NaN").doesNotContain("Infinity");
        return objectMapper.readTree(body);
    }

    @Test
    @DisplayName("GET /api/modules/clocksync returns valid overview with 5 nodes and limits")
    void overviewContract() throws Exception {
        JsonNode root = json(mockMvc.perform(get(BASE))
                .andExpect(status().isOk()));

        assertThat(root.has("status")).isTrue();
        assertThat(root.has("timeDaemonNodeId")).isTrue();
        assertThat(root.get("timeDaemonNodeId").asInt()).isEqualTo(1);
        assertThat(root.get("nodes")).hasSize(5);

        JsonNode node1 = root.get("nodes").get(0);
        assertThat(node1.get("nodeId").asInt()).isEqualTo(1);
        assertThat(node1.get("status").asText()).isEqualTo("UP");
        assertThat(node1.get("simulatedDrift").get("simulated").asBoolean()).isTrue();
        assertThat(node1.get("simulatedDrift").get("simulatedReason").asText()).isNotBlank();

        JsonNode limits = root.get("limits");
        assertThat(limits.get("maxTrafficSeconds").asInt()).isEqualTo(30);
        assertThat(limits.get("retainedEventsCapacity").asInt()).isEqualTo(2000);
    }

    @Test
    @DisplayName("POST /nodes/{id}/local-events records local Lamport event")
    void localEventEndpoint() throws Exception {
        JsonNode res = json(mockMvc.perform(post(BASE + "/nodes/1/local-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Local tick test\"}"))
                .andExpect(status().isOk()));

        assertThat(res.get("nodeId").asInt()).isEqualTo(1);
        assertThat(res.get("description").asText()).isEqualTo("Local tick test");
        assertThat(res.get("lamportTime").asLong()).isGreaterThanOrEqualTo(1L);
        assertThat(res.has("wallTime")).isTrue();
    }

    @Test
    @DisplayName("POST /messages transmits UDP Lamport message to live node")
    void sendMessageEndpoint() throws Exception {
        JsonNode res = json(mockMvc.perform(post(BASE + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\": 1, \"to\": 2, \"payload\": \"Ping\"}"))
                .andExpect(status().isOk()));

        assertThat(res.get("from").asInt()).isEqualTo(1);
        assertThat(res.get("to").asInt()).isEqualTo(2);
        assertThat(res.get("messageId").asLong()).isPositive();
        assertThat(res.get("deliveryStatus").asText()).isEqualTo("SENT");
        assertThat(res.get("deliveryNote").asText()).contains("port");
    }

    @Test
    @DisplayName("UDP honesty: POST /messages to crashed node returns 200 with deliveryStatus UNKNOWN")
    void udpHonestyCrashedReceiver() throws Exception {
        cluster.node(3).crash();

        JsonNode res = json(mockMvc.perform(post(BASE + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\": 1, \"to\": 3, \"payload\": \"Ghost message\"}"))
                .andExpect(status().isOk()));

        assertThat(res.get("from").asInt()).isEqualTo(1);
        assertThat(res.get("to").asInt()).isEqualTo(3);
        assertThat(res.get("deliveryStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(res.get("deliveryNote").asText()).contains("crashed node 3");
    }

    @Test
    @DisplayName("POST /messages from crashed node returns 409 NodeDownException")
    void sendFromCrashedNodeReturns409() throws Exception {
        cluster.node(2).crash();

        mockMvc.perform(post(BASE + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\": 2, \"to\": 1, \"payload\": \"Fail\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Node down"))
                .andExpect(jsonPath("$.nodeId").value(2));
    }

    @Test
    @DisplayName("POST /traffic initiates background traffic with 202 Accepted")
    void trafficEndpoint() throws Exception {
        JsonNode res = json(mockMvc.perform(post(BASE + "/traffic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seconds\": 1, \"messagesPerSecond\": 2}"))
                .andExpect(status().isAccepted()));

        assertThat(res.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(res.get("seconds").asInt()).isEqualTo(1);
        assertThat(res.get("messagesPerSecond").asInt()).isEqualTo(2);
        assertThat(res.get("sessionId").asText()).isNotBlank();
        await().atMost(Duration.ofSeconds(5)).until(() -> module.status() == ModuleStatus.IDLE);
    }

    @Test
    @DisplayName("POST /berkeley-rounds accepts round coordination with 202 Accepted")
    void berkeleyRoundEndpoint() throws Exception {
        JsonNode res = json(mockMvc.perform(post(BASE + "/berkeley-rounds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outlierThresholdMillis\": 400}"))
                .andExpect(status().isAccepted()));

        assertThat(res.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(res.get("daemonNodeId").asInt()).isEqualTo(1);
        assertThat(res.get("outlierThresholdMillis").asLong()).isEqualTo(400L);
        await().atMost(Duration.ofSeconds(5)).until(() -> module.status() == ModuleStatus.IDLE);
    }

    @Test
    @DisplayName("PUT /nodes/{id}/drift modifies node physical drift parameters")
    void updateDriftEndpoint() throws Exception {
        JsonNode res = json(mockMvc.perform(put(BASE + "/nodes/2/drift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initialOffsetMillis\": 85, \"driftRateMsPerSec\": 2.2}"))
                .andExpect(status().isOk()));

        assertThat(res.get("nodeId").asInt()).isEqualTo(2);
        assertThat(res.get("offsetMillis").asLong()).isEqualTo(85L);
        assertThat(res.get("driftRateMsPerSec").asDouble()).isEqualTo(2.2);
        assertThat(res.get("simulated").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("GET /verification reports causal check status")
    void verificationEndpoint() throws Exception {
        JsonNode res = json(mockMvc.perform(get(BASE + "/verification"))
                .andExpect(status().isOk()));

        assertThat(res.get("passed").asBoolean()).isTrue();
        assertThat(res.get("violationsCount").asInt()).isZero();
        assertThat(res.get("summary").asText()).contains("PASS");
        assertThat(res.has("retainedWindowNote")).isTrue();
    }

    @Test
    @DisplayName("GET /timeline returns events within limit and validates bounds")
    void timelineEndpoint() throws Exception {
        mockMvc.perform(post(BASE + "/nodes/1/local-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\": \"T1\"}"));

        JsonNode res = json(mockMvc.perform(get(BASE + "/timeline").param("limit", "5"))
                .andExpect(status().isOk()));

        assertThat(res.get("limit").asInt()).isEqualTo(5);
        assertThat(res.get("events").isArray()).isTrue();

        // Limit validation
        mockMvc.perform(get(BASE + "/timeline").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.limit").value("must be greater than or equal to 1"));

        mockMvc.perform(get(BASE + "/timeline").param("limit", "3000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.limit").value("must be less than or equal to 2000"));
    }

    @Test
    @DisplayName("traffic seconds beyond max returns 400 Bad Request")
    void trafficExceedsMaxReturns400() throws Exception {
        mockMvc.perform(post(BASE + "/traffic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seconds\": 50, \"messagesPerSecond\": 2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.seconds").value("must be between 1 and 30, was 50"));
    }

    @Test
    @DisplayName("local event on unknown node returns 404 UnknownNodeException")
    void localEventUnknownNodeReturns404() throws Exception {
        mockMvc.perform(post(BASE + "/nodes/99/local-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Ghost\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Unknown node"))
                .andExpect(jsonPath("$.nodeId").value(99));
    }
}
