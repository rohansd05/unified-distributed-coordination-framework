package com.udcf.modules.mpi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udcf.core.cluster.Cluster;
import com.udcf.modules.rmi.RmiModule;
import com.udcf.modules.rmi.RmiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A live node whose RMI never started stays a rank of the collective: PARTIAL, its transfer FAILED at
 * LOOKUP as CONNECTION_REFUSED, never hidden. Real RMI on 127.0.0.1, five nodes, over the HTTP API.
 *
 * <p>The blocker is a plain {@link Socket} bound to node 3's registry port and never listening, with
 * no socket option changed, created before anything in this context starts RMI. A socket without
 * SO_REUSEADDR cannot bind on Linux while any connection on that port is in TIME_WAIT, so this test
 * has its own class, context and ports that nothing else in the suite binds, and it never lets RMI
 * accept a connection on the blocked port (no TIME_WAIT is left there for a later run).</p>
 *
 * <p>Ports: {@code rmi-base=28694}, so node k's registry is 28694 + k (TCP 28695 to 28699; node 3 is
 * 28697), and {@code clock-base=28665} for the Experiment 3 clock service that starts with the
 * context (UDP 28666 to 28670). At class end the RMI services are stopped and the ports checked
 * free, and the context is closed.</p>
 */
@SpringBootTest(properties = {"udcf.cluster.ports.rmi-base=28694", "udcf.cluster.ports.clock-base=28665"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MpiNeverStartedNodeTest {

    private static final int RMI_BASE = 28694;
    private static final int BLOCKED_PORT = RMI_BASE + 3;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RmiModule rmiModule;

    @Autowired
    private Cluster cluster;

    @AfterAll
    void stopEverything() {
        rmiModule.reset();
        for (int k = 1; k <= cluster.size(); k++) {
            int port = RMI_BASE + k;
            await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> !RmiTestSupport.acceptThreadAlive(port) && RmiTestSupport.canBind(port));
        }
    }

    private JsonNode read(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private static JsonNode transferOf(JsonNode collective, int nodeId) {
        for (JsonNode t : collective.get("transfers")) {
            if (!"LOCAL".equals(t.get("status").asText()) && t.get("toNodeId").asInt() == nodeId) {
                return t;
            }
        }
        throw new AssertionError("no transfer for node " + nodeId);
    }

    @Test
    @DisplayName("a live node whose RMI never started stays a rank: PARTIAL, its transfer FAILED at LOOKUP, CONNECTION_REFUSED")
    void neverStartedNode() throws Exception {
        JsonNode c;
        JsonNode three;
        Socket blocker = new Socket();
        try {
            blocker.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), BLOCKED_PORT));   // bound, never listening
            c = read(mockMvc.perform(post("/api/modules/mpi/collectives").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"operation\":\"BROADCAST\",\"count\":1003,\"seed\":42}"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            three = read(mockMvc.perform(get("/api/modules/mpi")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).get("participants").get(2);
        } finally {
            blocker.close();
        }

        assertThat(three.get("nodeStatus").asText()).isEqualTo("UP");
        assertThat(three.get("rmiState").asText()).isEqualTo("NOT_STARTED");
        assertThat(three.get("startFailure").asText()).contains(String.valueOf(BLOCKED_PORT));
        assertThat(three.get("collectiveEndpointExported").asBoolean()).isFalse();
        assertThat(three.get("storedCollectiveIds").isNull()).isTrue();

        JsonNode failed = transferOf(c, 3);
        System.out.println("E9c outcome matrix | never-started node 3 | " + c.get("outcome").asText() + " | "
                + failed.get("failureStage").asText() + " | " + failed.get("failureKind").asText() + " | "
                + failed.get("failureClass").asText() + " <- " + failed.get("failureCauseClass").asText() + " | "
                + failed.get("timeToFailureNanos").asLong() + " ns");
        assertThat(c.get("outcome").asText()).isEqualTo("PARTIAL");
        assertThat(c.get("skippedNodeIds").toString()).isEqualTo("[]");
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        assertThat(failed.get("failureStage").asText()).isEqualTo("LOOKUP");
        assertThat(failed.get("failureKind").asText()).isEqualTo("CONNECTION_REFUSED");
        assertThat(failed.get("timeToFailureNanos").asLong()).isPositive();
        for (String f : List.of("latencyNanos", "requestBytes", "replyBytes", "senderLamportAtSend", "replyLamport",
                "lamportAfterReply", "verified")) {
            assertThat(failed.get(f).isNull()).as(f).isTrue();
        }
        for (int k : List.of(2, 4, 5)) {
            assertThat(transferOf(c, k).get("status").asText()).isEqualTo("DELIVERED");
        }
    }
}
