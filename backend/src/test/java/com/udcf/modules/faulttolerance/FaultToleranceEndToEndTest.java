package com.udcf.modules.faulttolerance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Experiment 8 end to end over real HTTP: controller, module, replication service (TCP),
 * shared failure detector (UDP heartbeats) and promotion. Every status read during both
 * scenarios checks that no two primaries coexist.
 *
 * <p>Own context. The HTTP port is random (ephemeral, not fixed). Every port base is overridden
 * into Track B's range 26700-26755: RMI 26700, clock 26710, election 26720, replication 26730,
 * requests 26740, MapReduce 26750. Only election (26721-26725) and replication (26731-26735)
 * bind. Timings are short (as FailoverFixture).</p>
 *
 * <p><b>Simulated asynchronous delay: 5000 ms.</b> An asynchronous update is lost when the primary
 * crashes before its delayed push. The test crashes as soon as one asynchronous update is
 * acknowledged (polled every 20 ms), so the push of that update is still 5 s away: the window is
 * 250 polling intervals wide and cannot be missed even on a stalled CI machine. No timing is asserted.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "udcf.cluster.ports.rmi-base=26700",
        "udcf.cluster.ports.clock-base=26710",
        "udcf.cluster.ports.election-base=26720",
        "udcf.cluster.ports.replication-base=26730",
        "udcf.cluster.ports.requests-base=26740",
        "udcf.cluster.ports.mapreduce-base=26750",
        "udcf.election.ok-timeout-millis=400",
        "udcf.election.coordinator-timeout-millis=1500",
        "udcf.election.probe-timeout-millis=150",
        "udcf.election.ring-completion-timeout-millis=3000",
        "udcf.election.heartbeat-interval-millis=100",
        "udcf.election.heartbeat-timeout-millis=600",
        "udcf.election.round-timeout-millis=10000",
        "udcf.replication.async-delay-millis=5000",
        "udcf.replication.timeout-millis=300",
        "udcf.faulttolerance.client.max-attempts=50",
        "udcf.faulttolerance.client.retry-delay-millis=100",
        "udcf.faulttolerance.role-query.retry-delay-millis=300",
        "udcf.faulttolerance.role-query.max-attempts=3",
        "udcf.faulttolerance.promotion.catch-up-timeout-millis=250",
        "udcf.faulttolerance.stream.max-rate-per-second=200"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FaultToleranceEndToEndTest {

    private static final String BASE = "/api/modules/faulttolerance";
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static HttpClient client;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final AtomicInteger observations = new AtomicInteger();

    @BeforeAll
    static void openClient() {
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterAll
    static void closeClient() {
        client.close();
    }

    @BeforeEach
    void cleanSlate() throws Exception {
        await().atMost(WAIT).until(() -> !"BUSY".equals(status().path("status").asText()));
        assertThat(send("POST", "/api/cluster/reset", null).statusCode()).isEqualTo(204);
    }

    private HttpResponse<String> send(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(String method, String path, String body, int expectedStatus) throws Exception {
        HttpResponse<String> response = send(method, path, body);
        assertThat(response.statusCode()).as(method + " " + path + ": " + response.body()).isEqualTo(expectedStatus);
        return objectMapper.readTree(response.body());
    }

    /** One status read; every one checks that no two primaries coexist. */
    private JsonNode status() throws Exception {
        JsonNode status = json("GET", BASE + "/status", null, 200);
        JsonNode splitBrain = status.path("splitBrain");
        assertThat(splitBrain.path("passed").asBoolean()).as(splitBrain.toString()).isTrue();
        assertThat(splitBrain.path("livePrimaries").size()).as(splitBrain.toString()).isLessThanOrEqualTo(1);
        assertThat(splitBrain.path("violationsSeen").asInt()).isZero();
        observations.incrementAndGet();
        return status;
    }

    private JsonNode node(JsonNode status, int nodeId) {
        for (JsonNode node : status.path("nodes")) {
            if (node.path("nodeId").asInt() == nodeId) {
                return node;
            }
        }
        throw new AssertionError("no node " + nodeId);
    }

    private JsonNode awaitRun(java.util.function.Predicate<JsonNode> done) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(WAIT).pollInterval(Duration.ofMillis(20)).until(() -> {
            status();
            HttpResponse<String> response = send("GET", BASE + "/runs/latest", null);
            if (response.statusCode() != 200) {
                return false;
            }
            last[0] = objectMapper.readTree(response.body());
            return done.test(last[0]);
        });
        return last[0];
    }

    @Test
    @DisplayName("synchronous: crash, failover to the new leader, zero loss; the recovered old primary demotes itself, "
            + "then takes over again as the leader; never two primaries")
    void synchronousCrashFailoverRecover() throws Exception {
        json("POST", BASE + "/stream/start", "{\"mode\":\"SYNCHRONOUS\",\"ratePerSecond\":20}", 202);
        await().atMost(WAIT).pollInterval(Duration.ofMillis(20)).until(() -> {
            JsonNode s = status();
            return s.path("stream").path("acknowledged").asInt() >= 5 && s.path("currentPrimaryId").asInt() == 5;
        });

        JsonNode crash = json("POST", BASE + "/crash-primary", null, 202);
        assertThat(crash.path("nodeId").asInt()).isEqualTo(5);
        JsonNode restored = awaitRun(run -> !run.path("dataLoss").isNull());
        assertThat(restored.path("outcome").asText()).isEqualTo("RESTORED");
        assertThat(restored.path("dataLoss").path("lost").asInt()).isZero();
        assertThat(restored.path("dataLoss").path("lost").isNull()).isFalse();
        assertThat(restored.path("dataLoss").path("acknowledged").asInt()).isGreaterThanOrEqualTo(5);
        long newEpoch = restored.path("newEpoch").asLong();
        assertThat(newEpoch).isGreaterThan(2);
        assertThat(restored.path("newPrimaryId").asInt()).isEqualTo(4);

        json("POST", BASE + "/recover", null, 202);
        JsonNode recovered = awaitRun(run -> !run.path("measurements").path("recoveryMillis").isNull());
        assertThat(recovered.path("milestones").toString()).contains("DEMOTED").contains("RESYNCHRONISED");
        // Node 5 won Bully again: once resynchronised it takes over at a higher epoch (planned handover).
        await().atMost(WAIT).pollInterval(Duration.ofMillis(20)).until(() -> status().path("currentPrimaryId").asInt() == 5);
        JsonNode end = status();
        assertThat(node(end, 5).path("role").asText()).isEqualTo("PRIMARY");
        assertThat(node(end, 5).path("epoch").asLong()).isEqualTo(newEpoch + 1);
        assertThat(node(end, 4).path("role").asText()).isEqualTo("BACKUP");
        json("POST", BASE + "/stream/stop", null, 200);
        assertThat(observations.get()).as("status observations, each checked for two primaries").isGreaterThan(10);
    }

    @Test
    @DisplayName("asynchronous: crash right after an acknowledged update loses it (simulated delay), none synchronous")
    void asynchronousCrashShowsInFlightLoss() throws Exception {
        json("POST", BASE + "/stream/start", "{\"mode\":\"ASYNCHRONOUS\",\"ratePerSecond\":20}", 202);
        await().atMost(WAIT).pollInterval(Duration.ofMillis(20))
                .until(() -> status().path("stream").path("acknowledged").asInt() >= 1);
        json("POST", BASE + "/crash-primary", null, 202);
        JsonNode run = awaitRun(r -> !r.path("dataLoss").isNull());
        JsonNode loss = run.path("dataLoss");
        assertThat(loss.path("lost").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(loss.path("lostSynchronous").asInt()).isZero();
        assertThat(loss.path("lostSynchronous").isNull()).isFalse();
        assertThat(loss.path("simulated").asBoolean()).isTrue();
        assertThat(loss.path("simulatedDelayMillis").asLong()).isEqualTo(5000);
        json("POST", BASE + "/stream/stop", null, 200);
    }
}
