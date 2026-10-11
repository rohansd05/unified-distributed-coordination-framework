package com.udcf.modules.rmi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udcf.core.cluster.Cluster;
import com.udcf.core.module.ModuleStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Experiment 1 end to end through the HTTP API with real RMI: a call succeeds, the target is
 * crashed through the cluster API, the next call is a remote failure returned as data, the node
 * is recovered, and a call succeeds again; plus Lamport time (the caller's own clock, no extra
 * tick), the events in causal order with single-line messages, and the event export (link L5).
 *
 * <p>Crashes nodes, so it has its own context: {@code udcf.cluster.ports.rmi-base=28760}, node k's
 * registry on 28760 + k (28761 to 28765; Jai's sub-range 28750-28799). At class end the RMI
 * services are stopped, the ports checked free, and the context closed.</p>
 */
@SpringBootTest(properties = "udcf.cluster.ports.rmi-base=28760")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RmiEndToEndTest {

    private static final int RMI_BASE = 28760;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RmiModule module;

    @Autowired
    private Cluster cluster;

    /**
     * A full cluster reset: recovers every node, resets the module (call ids restart at 1) AND clears
     * the event history, so a callId in the events belongs to this test only.
     */
    @BeforeEach
    void cleanStart() throws Exception {
        await().atMost(30, TimeUnit.SECONDS).until(() -> module.status() != ModuleStatus.BUSY);
        mockMvc.perform(post("/api/cluster/reset")).andExpect(status().isNoContent());
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

    private JsonNode call(String method, int from, int to, String extra) throws Exception {
        String body = "{\"fromNode\":" + from + ",\"targetNode\":" + to + ",\"method\":\"" + method + "\"" + extra + "}";
        String json = mockMvc.perform(post("/api/modules/rmi/calls").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private JsonNode ping(int from, int to) throws Exception {
        return call("PING", from, to, "");
    }

    /** The rmi events, in the API's causal order. */
    private List<JsonNode> events() throws Exception {
        String json = mockMvc.perform(get("/api/events").param("module", "rmi").param("limit", "500"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<JsonNode> events = new ArrayList<>();
        objectMapper.readTree(json).forEach(events::add);
        return events;
    }

    private List<JsonNode> eventsOf(long callId) throws Exception {
        return events().stream().filter(e -> e.get("data").has("callId") && e.get("data").get("callId").asLong() == callId)
                .toList();
    }

    @Test
    @DisplayName("call succeeds; crash the target via the cluster API; call is 200 REMOTE_FAILURE (refused or lost); recover; call succeeds")
    void crashAndRecover() throws Exception {
        assertThat(ping(1, 2).get("outcome").asText()).isEqualTo("SUCCESS");
        NodeEndpoint kept = module.remoteLookup().lookup(2);   // the same client channels the module uses

        mockMvc.perform(post("/api/cluster/nodes/2/crash")).andExpect(status().isOk());
        JsonNode failed = ping(1, 2);
        assertThat(failed.get("outcome").asText()).isEqualTo("REMOTE_FAILURE");
        assertThat(failed.get("failureKind").asText()).isIn("CONNECTION_REFUSED", "CONNECTION_LOST");
        assertThat(failed.get("roundTripNanos").isNull()).isTrue();
        assertThat(eventsOf(failed.get("id").asLong())).extracting(e -> e.get("type").asText())
                .containsExactly("CALL_FAILED");

        // Empty RMI's pooled connections to the dead node (E1b known issue: a pooled connection used
        // less than about 5 ms before is reused unchecked), so the call after the recovery is deterministic.
        RmiTestSupport.drainUntilRefused(() -> module.remoteLookup().lookup(2));
        RmiTestSupport.drainUntilRefused(() -> kept.ping(new CallContext(1, 0)));
        Reference.reachabilityFence(kept);

        mockMvc.perform(post("/api/cluster/nodes/2/recover")).andExpect(status().isOk());
        JsonNode again = ping(1, 2);
        assertThat(again.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(again.get("reply").get("nodeId").asInt()).isEqualTo(2);
        assertThat(again.get("id").asLong()).isEqualTo(failed.get("id").asLong() + 1);
    }

    @Test
    @DisplayName("Lamport: the caller's own clock sends and merges the reply, no event adds a tick, the cluster clock is untouched")
    void lamportUsesTheCallersClock() throws Exception {
        ping(1, 3);   // start the services first, so the next call is measured on its own
        long callerBefore = cluster.node(1).clock().current();
        long clusterBefore = cluster.clusterClock().current();

        JsonNode call = ping(1, 3);

        assertThat(call.get("senderLamportAtSend").asLong()).isEqualTo(callerBefore + 1);
        assertThat(call.get("replyLamport").asLong()).isGreaterThan(call.get("senderLamportAtSend").asLong());
        assertThat(call.get("senderLamportAfterReply").asLong()).isEqualTo(call.get("replyLamport").asLong() + 1)
                .isEqualTo(cluster.node(1).clock().current());
        assertThat(cluster.node(3).clock().current()).isEqualTo(call.get("replyLamport").asLong());
        assertThat(cluster.clusterClock().current()).isEqualTo(clusterBefore);
    }

    @Test
    @DisplayName("events: CALL_SERVED (target, reply time) before CALL_SUCCEEDED (caller, receive time), single-line messages")
    void eventsInCausalOrder() throws Exception {
        JsonNode call = call("ECHO", 1, 4, ",\"text\":\"line one\\nline two | pipe\"");

        List<JsonNode> events = eventsOf(call.get("id").asLong());

        assertThat(events).extracting(e -> e.get("type").asText()).containsExactly("CALL_SERVED", "CALL_SUCCEEDED");
        JsonNode served = events.get(0);
        JsonNode succeeded = events.get(1);
        assertThat(served.get("module").asText()).isEqualTo("rmi");
        assertThat(served.get("nodeId").asInt()).isEqualTo(4);
        assertThat(served.get("peerId").asInt()).isEqualTo(1);
        assertThat(served.get("lamportTime").asLong()).isEqualTo(call.get("replyLamport").asLong());
        assertThat(succeeded.get("nodeId").asInt()).isEqualTo(1);
        assertThat(succeeded.get("peerId").asInt()).isEqualTo(4);
        assertThat(succeeded.get("lamportTime").asLong()).isEqualTo(call.get("senderLamportAfterReply").asLong());
        assertThat(events()).allSatisfy(e -> assertThat(DisplayText.isSingleLine(e.get("message").asText()))
                .as(e.get("message").asText()).isTrue());
    }

    @Test
    @DisplayName("a self-call's events on one node: CALL_SERVED before CALL_SUCCEEDED; the comparison ends with COMPARISON_FINISHED")
    void selfCallEvents() throws Exception {
        JsonNode call = ping(5, 5);
        assertThat(eventsOf(call.get("id").asLong())).extracting(e -> e.get("type").asText() + "@" + e.get("nodeId").asInt())
                .containsExactly("CALL_SERVED@5", "CALL_SUCCEEDED@5");

        String body = "{\"fromNode\":5,\"targetNode\":5,\"method\":\"PING\"}";
        String json = mockMvc.perform(post("/api/modules/rmi/comparisons").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(json).get("id").asLong();
        assertThat(eventsOf(id)).extracting(e -> e.get("type").asText() + "/" + e.get("data").path("path").asText(""))
                .containsExactly("CALL_SERVED/LOCAL", "CALL_SUCCEEDED/LOCAL", "CALL_SERVED/REMOTE",
                        "CALL_SUCCEEDED/REMOTE", "COMPARISON_FINISHED/");
    }

    @Test
    @DisplayName("the event export (link L5) writes the REMOTE call's whole-millisecond latency, never for CALL_SERVED")
    void exportCarriesTheLatency() throws Exception {
        JsonNode call = ping(2, 1);

        String export = mockMvc.perform(get("/api/events/export").param("module", "rmi").param("limit", "100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        long millis = RmiModule.wholeMillis(call.get("roundTripNanos").asLong());
        assertThat(export.lines().filter(l -> l.contains("category=CALL_SUCCEEDED")))
                .anySatisfy(l -> assertThat(l).contains("node=2").contains("latency=" + millis + ".0"));
        assertThat(export.lines().filter(l -> l.contains("category=CALL_SERVED")))
                .allSatisfy(l -> assertThat(l).doesNotContain("latency="));
    }
}
