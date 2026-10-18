package com.udcf.modules.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.modules.multithreading.RequestsNodeService;
import com.udcf.modules.multithreading.dto.ThreadPoolStats;
import com.udcf.modules.rmi.RmiModule;
import com.udcf.modules.rmi.RmiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Experiment 10 end to end over real HTTP (link L6): HTTP to the module, RMI collectives to every node, each
 * block multiplied on its node's real Experiment 2 executor, the blocks fetched over RMI and compared bit for bit
 * with the sequential product. Also the series done-when: speedup for three sizes. No speedup threshold is
 * asserted (all nodes share one machine); only that it is known.
 *
 * <p>Its own context with a three-node cluster and Jai's test ports (track file, E10c port table): RMI base 28711
 * (TCP 28712-28714), requests base 28728 (TCP 28729-28731), clock base 28741 (UDP 28742-28744). The HTTP server
 * takes an ephemeral port from the OS (RANDOM_PORT). The election, replication and mapreduce bases are 28610,
 * 28620 and 28630, numbers only, never bound (checked). At class end RMI and the request listeners are stopped
 * and their ports checked free.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"udcf.cluster.size=3",
        "udcf.cluster.ports.rmi-base=28711", "udcf.cluster.ports.requests-base=28728", "udcf.cluster.ports.clock-base=28741",
        "udcf.cluster.ports.election-base=28610", "udcf.cluster.ports.replication-base=28620",
        "udcf.cluster.ports.mapreduce-base=28630"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MatrixEndToEndHttpTest {

    private static final String BASE = "/api/modules/matrix";
    private static final int RMI_BASE = 28711;
    private static final int REQUESTS_BASE = 28728;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RmiModule rmiModule;

    @Autowired
    private Cluster cluster;

    @BeforeEach
    void cleanStart() {
        assertThat(http.postForEntity("/api/cluster/reset", null, String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(http.postForEntity(BASE + "/start", null, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
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

    private JsonNode read(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode node = objectMapper.readTree(response.getBody());
        MatrixJsonChecks.assertSafeNumbers(node);
        MatrixJsonChecks.assertNoDerivedSeed(response.getBody(), List.of(42L));
        return node;
    }

    private JsonNode run(int size) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return read(http.postForEntity(BASE + "/runs", new HttpEntity<>("{\"rootNode\":1,\"size\":" + size + ",\"seed\":42}", headers),
                String.class));
    }

    private long completedTasks(int nodeId) {
        return RequestsNodeService.find(cluster.node(nodeId)).flatMap(RequestsNodeService::snapshot)
                .map(ThreadPoolStats::completedTasks).orElse(-1L);
    }

    @Test
    @DisplayName("COMPLETE with exact equality for sizes 50 and 100; only clock, rmi and requests run, on this class's ports")
    void exactForTwoSizes() throws Exception {
        for (int size : new int[] {50, 100}) {
            JsonNode run = run(size);
            assertThat(run.get("outcome").asText()).as("size %d", size).isEqualTo("COMPLETE");
            assertThat(run.get("rankNodeIds")).hasSize(3);
            JsonNode v = run.get("verification");
            assertThat(v.get("passed").asBoolean()).isTrue();
            assertThat(v.get("mismatchedElements").asLong()).isZero();
            assertThat(v.get("elementsCompared").asLong()).isEqualTo((long) size * size);
            assertThat(v.get("checksumsMatch").asBoolean()).isTrue();
            assertThat(v.get("parallelChecksum").asText()).isEqualTo(v.get("sequentialChecksum").asText());
            assertThat(run.get("inputsDelivered").asBoolean()).isTrue();
        }
        for (ClusterNode node : cluster.nodes()) {
            assertThat(node.runningServices()).as("node %d", node.id()).containsExactlyInAnyOrder("clock", "rmi", "requests");
            assertThat(node.ports().rmi()).isBetween(28712, 28714);
            assertThat(node.ports().requests()).isBetween(28729, 28731);
            assertThat(node.ports().clock()).isBetween(28742, 28744);
        }
    }

    @Test
    @DisplayName("series: after sizes 50, 100 and 200, speedup is known for three sizes and the series is done (no threshold)")
    void seriesDone() throws Exception {
        run(50);
        run(100);
        run(200);

        JsonNode series = read(http.getForEntity(BASE + "/series", String.class));
        assertThat(series.get("sizesWithSpeedup").asInt()).isEqualTo(3);
        assertThat(series.get("done").asBoolean()).isTrue();
        assertThat(series.get("points")).hasSize(3);
        for (JsonNode point : series.get("points")) {
            assertThat(point.get("speedup").isNumber()).isTrue();
            assertThat(point.get("sourceOutcome").asText()).isEqualTo("COMPLETE");
            assertThat(point.get("ranks").asInt()).isEqualTo(3);
        }
        assertThat(series.get("gaps")).hasSize(1);
        assertThat(series.get("gaps").get(0).get("size").asInt()).isEqualTo(316);
    }

    @Test
    @DisplayName("the blocks run on each node's Experiment 2 executor, outside Experiment 2's request history")
    void runsOnExperimentTwo() throws Exception {
        long[] before = {completedTasks(1), completedTasks(2), completedTasks(3)};
        assertThat(before).doesNotContain(-1L);   // the start prepared every listener (-1: not running)

        run(60);

        await().atMost(5, TimeUnit.SECONDS).until(() -> completedTasks(1) == before[0] + 1
                && completedTasks(2) == before[1] + 1 && completedTasks(3) == before[2] + 1);
        for (int nodeId = 1; nodeId <= 3; nodeId++) {
            JsonNode requests = objectMapper.readTree(http.getForEntity("/api/modules/multithreading/nodes/" + nodeId
                    + "/requests", String.class).getBody());
            assertThat(requests).as("node %d", nodeId).isEmpty();
        }
    }
}
