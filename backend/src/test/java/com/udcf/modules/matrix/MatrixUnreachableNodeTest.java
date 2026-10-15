package com.udcf.modules.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udcf.core.cluster.Cluster;
import com.udcf.modules.multithreading.RequestsNodeService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A live node whose RMI endpoint cannot be reached gives a PARTIAL run, never a hang and never a hidden hole:
 * node 3 stays up, but its RMI registry port is held by a plain socket, so its RMI never starts; the root's
 * scatter to it fails at LOOKUP, its compute and fetch are not attempted, and its rows are a reported hole.
 *
 * <p>Its own class, context and ports (the E9c lesson on Linux: a blocker socket's bind fails while any
 * connection on that port is in TIME_WAIT, so nothing else may ever accept connections on the blocked port):
 * RMI base 28718 (RMI binds TCP 28719 and 28720; TCP 28721, node 3's registry port, is held by a
 * {@code java.net.Socket} bound and never listening, no socket option set, closed in {@code finally}), requests
 * base 28731 (TCP 28732-28734), clock base 28745 (UDP 28746-28748). The election, replication and mapreduce bases
 * are 28610, 28620 and 28630, numbers only, never bound. One test.</p>
 */
@SpringBootTest(properties = {"udcf.cluster.size=3", "udcf.cluster.ports.rmi-base=28718",
        "udcf.cluster.ports.requests-base=28731", "udcf.cluster.ports.clock-base=28745",
        "udcf.cluster.ports.election-base=28610", "udcf.cluster.ports.replication-base=28620",
        "udcf.cluster.ports.mapreduce-base=28630"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MatrixUnreachableNodeTest {

    private static final int BLOCKED_PORT = 28721;

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
        cluster.nodes().forEach(node -> RequestsNodeService.find(node).ifPresent(RequestsNodeService::stop));
        for (int port : new int[] {28719, 28720, 28732, 28733, 28734}) {
            await().atMost(5, TimeUnit.SECONDS).until(() -> !RmiTestSupport.acceptThreadAlive(port) && RmiTestSupport.canBind(port));
        }
    }

    private JsonNode body(String content) throws Exception {
        JsonNode node = objectMapper.readTree(content);
        MatrixJsonChecks.assertSafeNumbers(node);
        MatrixJsonChecks.assertNoDerivedSeed(content, List.of(42L));
        return node;
    }

    @Test
    @DisplayName("node 3 live but its RMI port held: start reports the failure; the run is PARTIAL with node 3 a reported hole")
    void unreachableNodeGivesPartial() throws Exception {
        try (Socket blocker = new Socket()) {
            blocker.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), BLOCKED_PORT));

            JsonNode started = body(mockMvc.perform(post("/api/modules/matrix/start")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            JsonNode node3 = started.get("participants").get(2);
            assertThat(node3.get("nodeStatus").asText()).isEqualTo("UP");
            assertThat(node3.get("rmiState").asText()).isEqualTo("NOT_STARTED");
            assertThat(node3.get("startFailure").asText()).isNotBlank();
            assertThat(node3.get("matrixEndpointExported").asBoolean()).isFalse();
            assertThat(node3.get("keptRunIds").isNull()).isTrue();
            assertThat(node3.get("requestListenerRunning").asBoolean()).isTrue();   // prepared; only its RMI is blocked
            assertThat(started.get("participants").get(1).get("matrixEndpointExported").asBoolean()).isTrue();

            JsonNode run = body(mockMvc.perform(post("/api/modules/matrix/runs").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"size\":30,\"seed\":42}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

            assertThat(run.get("outcome").asText()).isEqualTo("PARTIAL");
            assertThat(run.get("skippedNodeIds")).isEmpty();   // live, so a rank
            JsonNode scatterToThree = run.get("scatter").get("transfers").get(2);
            assertThat(scatterToThree.get("toNodeId").asInt()).isEqualTo(3);
            assertThat(scatterToThree.get("status").asText()).isEqualTo("FAILED");
            assertThat(scatterToThree.get("failureStage").asText()).isEqualTo("LOOKUP");
            assertThat(scatterToThree.get("failureKind").asText()).isEqualTo("CONNECTION_REFUSED");
            assertThat(scatterToThree.get("latencyNanos").isNull()).isTrue();
            assertThat(scatterToThree.get("timeToFailureNanos").isNumber()).isTrue();
            JsonNode compute = run.get("computes").get(2);
            assertThat(compute.get("status").asText()).isEqualTo("NOT_ATTEMPTED");
            assertThat(compute.get("notAttemptedReason").asText()).startsWith("Its input did not arrive verified");
            assertThat(compute.get("computeNanos").isNull()).isTrue();
            assertThat(run.get("gather").get(2).get("status").asText()).isEqualTo("NOT_ATTEMPTED");
            assertThat(run.get("computes").get(1).get("status").asText()).isEqualTo("DONE");

            JsonNode v = run.get("verification");
            assertThat(v.get("blocksMissing").asInt()).isEqualTo(1);
            assertThat(v.get("rowsReceived").asInt()).isEqualTo(20);
            assertThat(v.get("mismatchedElements").asLong()).isZero();
            assertThat(v.get("passed").isNull()).isTrue();
            assertThat(v.get("parallelChecksum").isNull()).isTrue();
            assertThat(v.get("checksumsMatch").isNull()).isTrue();
            JsonNode derived = run.get("timing").get("derived");
            assertThat(derived.get("speedup").isNull()).isTrue();
            assertThat(derived.get("computeBoundSpeedup").isNull()).isTrue();
            assertThat(derived.get("speedupWithheldReason").asText())
                    .isEqualTo("No speedup: the run was partial, so its parallel time is not the time of the whole product.");
        }
    }
}
