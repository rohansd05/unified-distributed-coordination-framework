package com.udcf.modules.mapreduce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.module.ModuleStatus;
import com.udcf.modules.mapreduce.dto.RunState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP API of /api/modules/mapreduce: the contract, every error status, and the request
 * body limit over a real connection (fixed length and chunked).
 *
 * <p>Own context: mapreduce ports 24541-24545 (Track D block 24501-24599) and a 4096-byte
 * upload cap, so the body limit is 4 x 1366 + 8192 = 13656 bytes. The HTTP port is random.
 * The context is closed after the class, so its mapreduce worker and run threads are stopped
 * and joined and never outlive these tests (MapReducePipelineTest checks the whole JVM for
 * {@code udcf-mapreduce-*} threads).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "udcf.cluster.ports.mapreduce-base=24540",
        "udcf.mapreduce.upload-max-bytes=4096"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MapReduceControllerTest {

    private static final String BASE = "/api/modules/mapreduce";
    private static final int BODY_LIMIT = 4 * 1366 + 8192;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Cluster cluster;

    @Autowired
    private MapReduceModule module;

    @LocalServerPort
    private int port;

    @AfterEach
    void tearDown() {
        await().atMost(MapReduceModuleHarness.RUN_TIMEOUT).until(() -> module.status() != ModuleStatus.BUSY);
        cluster.nodes().forEach(ClusterNode::recover);
        module.reset();
    }

    private JsonNode json(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("NaN").doesNotContain("Infinity");
        return objectMapper.readTree(body);
    }

    private String body(String jobId, String inputType, Map<String, Object> upload, Integer crashWorkerId)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("inputType", inputType);
        if (upload != null) {
            body.put("upload", upload);
        }
        if (crashWorkerId != null) {
            body.put("crashWorkerId", crashWorkerId);
        }
        return objectMapper.writeValueAsString(body);
    }

    private static Map<String, Object> upload(String fileName, String contentType, byte[] bytes) {
        Map<String, Object> upload = new LinkedHashMap<>();
        upload.put("fileName", fileName);
        upload.put("contentType", contentType);
        upload.put("contentBase64", bytes == null ? null : Base64.getEncoder().encodeToString(bytes));
        return upload;
    }

    private ResultActions postRun(String json) throws Exception {
        return mockMvc.perform(post(BASE + "/runs").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private JsonNode awaitRun(String runId) throws Exception {
        await().atMost(MapReduceModuleHarness.RUN_TIMEOUT).until(() -> module.run(runId).state() != RunState.RUNNING);
        await().atMost(MapReduceModuleHarness.RUN_TIMEOUT).until(() -> module.status() != ModuleStatus.BUSY);
        return json(mockMvc.perform(get(BASE + "/runs/" + runId)).andExpect(status().isOk()));
    }

    private void expectFieldError(String json, String field) throws Exception {
        postRun(json).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request parameters"))
                .andExpect(jsonPath("$.errors['" + field + "']").isNotEmpty());
    }

    @Test
    @DisplayName("GET overview: status, jobs, input types, coordinator, workers, limits, latestRun null, notes")
    void overview() throws Exception {
        JsonNode root = json(mockMvc.perform(get(BASE)).andExpect(status().isOk()));

        assertThat(root.get("status").asText()).isIn("IDLE", "RUNNING");
        assertThat(root.get("currentAction").isNull()).isTrue();
        assertThat(root.get("jobs")).hasSize(3);
        assertThat(root.get("jobs").get(0).get("id").asText()).isEqualTo("word-count");
        assertThat(root.get("inputTypes")).hasSize(3);
        assertThat(root.get("coordinatorId").asInt()).isEqualTo(1);
        assertThat(root.get("workerIds")).hasSize(5);
        assertThat(root.get("limits").get("uploadMaxBytes").asInt()).isEqualTo(4096);
        assertThat(root.get("limits").get("requestBodyMaxBytes").asInt()).isEqualTo(BODY_LIMIT);
        assertThat(root.has("latestRun")).isTrue();
        assertThat(root.get("latestRun").isNull()).isTrue();
        assertThat(root.get("notes").size()).isPositive();
    }

    @Test
    @DisplayName("POST a sample run: 202 RUNNING with nulls present, then COMPLETED via /runs/{id}, /runs/latest and /runs")
    void sampleRun() throws Exception {
        JsonNode accepted = json(postRun(body("word-count", "SAMPLE", null, null)).andExpect(status().isAccepted()));
        String runId = accepted.get("runId").asText();
        assertThat(accepted.get("state").asText()).isEqualTo("RUNNING");
        for (String nullField : new String[]{"finishedAt", "report", "error", "crash", "notice"}) {
            assertThat(accepted.has(nullField)).as(nullField).isTrue();
            assertThat(accepted.get(nullField).isNull()).as(nullField).isTrue();
        }

        JsonNode run = awaitRun(runId);
        assertThat(run.get("state").asText()).isEqualTo("COMPLETED");
        assertThat(run.get("report").get("reducers").asInt()).isEqualTo(5);
        assertThat(run.get("report").get("inputLinesDropped").isNull()).isTrue();
        assertThat(run.get("report").get("timings").get("totalMillis").isNumber()).isTrue();
        assertThat(run.get("report").get("resultsTruncated").asBoolean()).isFalse();
        assertThat(run.get("report").get("results")).hasSize(run.get("report").get("resultKeys").asInt());

        mockMvc.perform(get(BASE + "/runs/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId));
        mockMvc.perform(get(BASE + "/runs")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runId").value(runId))
                .andExpect(jsonPath("$[0].state").value("COMPLETED"));
    }

    @Test
    @DisplayName("POST an upload run: 202, then COMPLETED with only the sanitised file name shown")
    void uploadRun() throws Exception {
        String json = body("word-count", "UPLOAD",
                upload("C:\\fakepath\\notes.txt", "text/plain", "one two two".getBytes(StandardCharsets.UTF_8)), null);
        String runId = json(postRun(json).andExpect(status().isAccepted())).get("runId").asText();

        JsonNode run = awaitRun(runId);

        assertThat(run.get("state").asText()).isEqualTo("COMPLETED");
        assertThat(run.get("inputName").asText()).isEqualTo("notes.txt");
        assertThat(run.get("report").get("resultKeys").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("404 for an unknown run, for latest when none is kept, and for an unknown job")
    void notFound() throws Exception {
        mockMvc.perform(get(BASE + "/runs/zzzzzzzz")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Unknown run"))
                .andExpect(jsonPath("$.runId").value("zzzzzzzz"));
        mockMvc.perform(get(BASE + "/runs/latest")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No MapReduce run has been started yet"));
        postRun(body("nope", "SAMPLE", null, null)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Unknown job"))
                .andExpect(jsonPath("$.jobId").value("nope"));
    }

    @Test
    @DisplayName("400 with field messages for missing fields, a bad input type and every upload problem")
    void validation() throws Exception {
        expectFieldError(body(null, "SAMPLE", null, null), "jobId");
        expectFieldError(body("word-count", null, null, null), "inputType");
        postRun(body("word-count", "FLOPPY", null, null)).andExpect(status().isBadRequest());
        expectFieldError(body("word-count", "UPLOAD", null, null), "upload");
        expectFieldError(body("word-count", "SAMPLE", upload("a.txt", "text/plain", new byte[]{'x'}), null), "upload");
        byte[] ok = "x".getBytes(StandardCharsets.UTF_8);
        expectFieldError(body("word-count", "UPLOAD", upload("a.csv", "text/plain", ok), null), "upload.fileName");
        expectFieldError(body("word-count", "UPLOAD", upload(null, "text/plain", ok), null), "upload.fileName");
        expectFieldError(body("word-count", "UPLOAD", upload("a.txt", "image/png", ok), null), "upload.contentType");
        expectFieldError(body("word-count", "UPLOAD", upload("a.txt", "text/plain", null), null), "upload.contentBase64");
        expectFieldError(body("word-count", "UPLOAD", upload("a.txt", "text/plain", new byte[0]), null), "upload.contentBase64");
        expectFieldError(body("word-count", "UPLOAD", upload("a.txt", "text/plain", new byte[4097]), null), "upload.contentBase64");
        expectFieldError(body("word-count", "UPLOAD", upload("a.txt", "text/plain", new byte[]{(byte) 0xFF}), null), "upload.contentBase64");
        expectFieldError(body("word-count", "UPLOAD", upload("a.txt", "text/plain", new byte[]{'a', 0}), null), "upload.contentBase64");
        expectFieldError(body("word-count", "UPLOAD", upload("a.txt", "text/plain", new byte[]{'a', 7}), null), "upload.contentBase64");
        assertThat(module.runs()).isEmpty();
    }

    @Test
    @DisplayName("400 for a crash worker that is the coordinator, unknown or down")
    void badCrashWorker() throws Exception {
        expectFieldError(body("word-count", "SAMPLE", null, 1), "crashWorkerId");
        expectFieldError(body("word-count", "SAMPLE", null, 9), "crashWorkerId");
        cluster.crash(5);
        postRun(body("word-count", "SAMPLE", null, 5)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.crashWorkerId").value("node 5 is down; choose a live worker"));
    }

    @Test
    @DisplayName("409 while a run is active, naming the action in progress")
    void busy() throws Exception {
        try (var ticket = module.guard().begin("test action")) {
            postRun(body("word-count", "SAMPLE", null, null)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Module busy"))
                    .andExpect(jsonPath("$.actionInProgress").value("test action"));
        }
    }

    @Test
    @DisplayName("409 when every node is down")
    void noLiveWorker() throws Exception {
        cluster.nodes().forEach(node -> cluster.crash(node.id()));

        postRun(body("word-count", "SAMPLE", null, null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("No live worker"));
    }

    // ------------------------------------------------------------------ C3: request body limit over real HTTP

    /** A JSON run body of exactly {@code size} bytes: an upload at the cap, padded with JSON whitespace. */
    private byte[] bodyOfSize(int size) throws Exception {
        byte[] file = "word ".repeat(4096 / 5 + 1).substring(0, 4096).getBytes(StandardCharsets.UTF_8);
        String json = body("word-count", "UPLOAD", upload("cap.txt", "text/plain", file), null);
        assertThat(json.length()).isLessThanOrEqualTo(size);
        return (json.substring(0, json.length() - 1) + " ".repeat(size - json.length()) + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private HttpResponse<String> send(byte[] body, boolean chunked) throws Exception {
        HttpRequest.BodyPublisher publisher = chunked
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                : HttpRequest.BodyPublishers.ofByteArray(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + BASE + "/runs"))
                .header("Content-Type", "application/json")
                .POST(publisher)
                .build();
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    @DisplayName("C3: a declared Content-Length over the limit gets 413 before the controller runs")
    void bodyTooLargeFixedLength() throws Exception {
        HttpResponse<String> response = send(bodyOfSize(BODY_LIMIT + 1), false);

        assertThat(response.statusCode()).isEqualTo(413);
        JsonNode problem = objectMapper.readTree(response.body());
        assertThat(problem.get("title").asText()).isEqualTo("Request body too large");
        assertThat(problem.get("limitBytes").asInt()).isEqualTo(BODY_LIMIT);
        assertThat(module.runs()).isEmpty();
    }

    @Test
    @DisplayName("C3: a chunked body over the limit is cut off with 413; the controller never runs")
    void bodyTooLargeChunked() throws Exception {
        HttpResponse<String> response = send(bodyOfSize(BODY_LIMIT + 1000), true);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(objectMapper.readTree(response.body()).get("title").asText()).isEqualTo("Request body too large");
        assertThat(module.runs()).isEmpty();
    }

    @Test
    @DisplayName("C3: an upload at the cap in a body of exactly the limit is accepted, fixed length and chunked")
    void bodyAtLimit() throws Exception {
        for (boolean chunked : new boolean[]{false, true}) {
            HttpResponse<String> response = send(bodyOfSize(BODY_LIMIT), chunked);

            assertThat(response.statusCode()).as("chunked=" + chunked).isEqualTo(202);
            String runId = objectMapper.readTree(response.body()).get("runId").asText();
            assertThat(awaitRun(runId).get("state").asText()).isEqualTo("COMPLETED");
        }
    }
}
