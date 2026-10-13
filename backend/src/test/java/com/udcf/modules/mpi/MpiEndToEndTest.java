package com.udcf.modules.mpi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udcf.core.cluster.Cluster;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.rmi.RmiModule;
import com.udcf.modules.rmi.RmiTestSupport;
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
 * Experiment 9 end to end over the HTTP API and real RMI on 127.0.0.1, five nodes: broadcast,
 * scatter and gather with every checksum checked independently of the module (from
 * {@link PayloadGenerator} and from what each node's endpoint really stored), a node crashed at the
 * start (skipped), and a live node whose RMI never started (PARTIAL: LOOKUP, refused).
 *
 * <p>Own context with Jai's test ports: {@code rmi-base=28675}, so node k's registry is 28675 + k (TCP
 * 28676 to 28680), and {@code clock-base=28685} for the Experiment 3 clock service that starts with
 * the context (UDP 28686 to 28690). The never-started case binds a plain {@link Socket} (never
 * listening, no socket option changed) to 28678, node 3's registry port, before RMI starts. Each
 * test starts with a cluster reset. At class end the RMI services are stopped and the ports checked
 * free.</p>
 */
@SpringBootTest(properties = {"udcf.cluster.ports.rmi-base=28675", "udcf.cluster.ports.clock-base=28685"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MpiEndToEndTest {

    private static final int RMI_BASE = 28675;
    private static final int COUNT = 1003;
    private static final long SEED = 42;

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

    private JsonNode run(String operation) throws Exception {
        String body = "{\"operation\":\"" + operation + "\",\"count\":" + COUNT + ",\"seed\":" + SEED + "}";
        String json = mockMvc.perform(post("/api/modules/mpi/collectives").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private JsonNode overview() throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/api/modules/mpi")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** What node k's CollectiveEndpoint really stored for collective {@code id}. */
    private double[] received(int nodeId, long id) {
        return CollectiveEndpointExport.current(cluster.node(nodeId)).orElseThrow().received(id).orElseThrow();
    }

    private static JsonNode transferOf(JsonNode collective, int nodeId) {
        for (JsonNode t : collective.get("transfers")) {
            if (!"LOCAL".equals(t.get("status").asText())
                    && (t.get("toNodeId").asInt() == nodeId || t.get("fromNodeId").asInt() == nodeId)) {
                return t;
            }
        }
        throw new AssertionError("no transfer for node " + nodeId);
    }

    private static void assertLamport(JsonNode transfer) {
        assertThat(transfer.get("replyLamport").asLong()).isGreaterThan(transfer.get("senderLamportAtSend").asLong());
        assertThat(transfer.get("lamportAfterReply").asLong()).isGreaterThan(transfer.get("replyLamport").asLong());
    }

    @Test
    @DisplayName("broadcast: COMPLETE on every live node; each node stored exactly the root's vector (checksum computed here)")
    void broadcast() throws Exception {
        JsonNode c = run("BROADCAST");
        String expected = VectorChecksum.of(PayloadGenerator.vector(SEED, COUNT));

        assertThat(c.get("outcome").asText()).isEqualTo("COMPLETE");
        assertThat(c.get("inputChecksum").asText()).isEqualTo(expected);
        for (int k = 2; k <= 5; k++) {
            JsonNode t = transferOf(c, k);
            assertThat(t.get("status").asText()).isEqualTo("DELIVERED");
            assertThat(t.get("reportedChecksum").asText()).isEqualTo(expected);
            assertThat(VectorChecksum.of(received(k, c.get("id").asLong()))).isEqualTo(expected);
            assertThat(cluster.node(k).clock().current()).isGreaterThanOrEqualTo(t.get("replyLamport").asLong());
            assertLamport(t);
        }
        assertThat(overview().get("participants").get(1).get("storedCollectiveIds").toString()).isEqualTo("[1]");
    }

    @Test
    @DisplayName("scatter: COMPLETE; node k stored exactly block k-1 of the vector (201, 201, 201, 200, 200 values)")
    void scatter() throws Exception {
        JsonNode c = run("SCATTER");
        double[] vector = PayloadGenerator.vector(SEED, COUNT);
        List<BlockPartition.Block> blocks = BlockPartition.of(COUNT, 5);

        assertThat(c.get("outcome").asText()).isEqualTo("COMPLETE");
        assertThat(c.get("verification").get("wholeVectorMatches").asBoolean()).isTrue();
        assertThat(blocks).extracting(BlockPartition.Block::length).containsExactly(201, 201, 201, 200, 200);
        for (int k = 2; k <= 5; k++) {
            String expected = VectorChecksum.of(BlockPartition.slice(vector, blocks.get(k - 1)));
            JsonNode t = transferOf(c, k);
            assertThat(t.get("elementCount").asInt()).isEqualTo(blocks.get(k - 1).length());
            assertThat(t.get("offset").asInt()).isEqualTo(blocks.get(k - 1).offset());
            assertThat(t.get("reportedChecksum").asText()).isEqualTo(expected);
            assertThat(VectorChecksum.of(received(k, c.get("id").asLong()))).isEqualTo(expected);
            assertLamport(t);
        }
    }

    @Test
    @DisplayName("gather: COMPLETE; each block and the assembled vector equal each node's contribution computed here")
    void gather() throws Exception {
        JsonNode c = run("GATHER");
        List<BlockPartition.Block> blocks = BlockPartition.of(COUNT, 5);
        double[] assembled = new double[COUNT];
        for (BlockPartition.Block block : blocks) {
            double[] part = PayloadGenerator.contribution(block.rank() + 1, block.length(), SEED);
            System.arraycopy(part, 0, assembled, block.offset(), block.length());
            JsonNode gathered = c.get("gathered").get(block.rank());
            assertThat(gathered.get("nodeId").asInt()).isEqualTo(block.rank() + 1);
            assertThat(gathered.get("checksum").asText()).isEqualTo(VectorChecksum.of(part));
        }

        assertThat(c.get("outcome").asText()).isEqualTo("COMPLETE");
        assertThat(c.get("inputChecksum").asText()).isEqualTo(VectorChecksum.of(assembled));
        assertThat(c.get("verification").get("passed").asBoolean()).isTrue();
        assertThat(c.get("verification").get("wholeVectorMatches").asBoolean()).isTrue();
        for (int k = 2; k <= 5; k++) {
            assertLamport(transferOf(c, k));
        }
    }

    @Test
    @DisplayName("a node crashed at the start is skipped and listed; the collective COMPLETEs on the live nodes")
    void crashedAtStartIsSkipped() throws Exception {
        mockMvc.perform(post("/api/cluster/nodes/3/crash")).andExpect(status().isOk());

        JsonNode c = run("BROADCAST");

        assertThat(c.get("outcome").asText()).isEqualTo("COMPLETE");
        assertThat(c.get("skippedNodeIds").toString()).isEqualTo("[3]");
        assertThat(c.get("rankNodeIds").toString()).isEqualTo("[1,2,4,5]");
        assertThat(c.get("transfers")).hasSize(4);
    }

    @Test
    @DisplayName("a live node whose RMI never started stays a rank: PARTIAL, its transfer FAILED at LOOKUP, CONNECTION_REFUSED")
    void neverStartedNode() throws Exception {
        JsonNode c;
        try (Socket squatter = new Socket()) {
            squatter.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), RMI_BASE + 3));   // bound, never listening
            c = run("BROADCAST");

            JsonNode three = overview().get("participants").get(2);
            assertThat(three.get("nodeStatus").asText()).isEqualTo("UP");
            assertThat(three.get("rmiState").asText()).isEqualTo("NOT_STARTED");
            assertThat(three.get("startFailure").asText()).contains(String.valueOf(RMI_BASE + 3));
            assertThat(three.get("collectiveEndpointExported").asBoolean()).isFalse();
            assertThat(three.get("storedCollectiveIds").isNull()).isTrue();
        }

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

        // the port is free again: the next collective starts RMI on node 3 and completes
        assertThat(run("BROADCAST").get("outcome").asText()).isEqualTo("COMPLETE");
    }
}
