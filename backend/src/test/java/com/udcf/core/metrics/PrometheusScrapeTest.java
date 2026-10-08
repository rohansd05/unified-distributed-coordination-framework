package com.udcf.core.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards what Prometheus actually scrapes under the default (local) profile: node_id on
 * every distributed_* series (R5), the global application and mode tags, and _total
 * exactly once on counters.
 *
 * <p>{@code @AutoConfigureObservability} turns the Prometheus exporter back on (Spring Boot
 * disables exporters in tests), which also gives this class its own context. It only
 * reads state, apart from one Exp 2 batch of one request on node 1 so the request counter
 * and timer exist; that starts node 1's requests service on a test-only port (44201).</p>
 */
@SpringBootTest(properties = "udcf.cluster.ports.requests-base=44200")
@AutoConfigureMockMvc
@AutoConfigureObservability
class PrometheusScrapeTest {

    @Autowired
    private MockMvc mockMvc;

    private List<String> sampleLines;

    @BeforeEach
    void scrape() throws Exception {
        mockMvc.perform(post("/api/modules/multithreading/nodes/1/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1,\"type\":\"CPU_HASH\",\"payloadSize\":1}"))
                .andExpect(status().isAccepted());
        // The batch drains in the background; wait until its timer sample is scraped.
        await().atMost(10, TimeUnit.SECONDS).until(() -> {
            readSamples();
            return sampleLines.stream().anyMatch(line ->
                    line.startsWith("distributed_request_duration_seconds_count{") && line.contains("node_id=\"1\""));
        });
    }

    private void readSamples() throws Exception {
        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        sampleLines = Arrays.stream(body.split("\n"))
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .toList();
    }

    private List<String> samples(String metric) {
        return sampleLines.stream().filter(line -> line.startsWith(metric + "{")).toList();
    }

    @Test
    @DisplayName("distributed_node_status is scraped for nodes 1..5 with application and mode tags")
    void nodeStatusForEveryNode() {
        List<String> status = samples(MetricNames.NODE_STATUS);

        assertThat(status).hasSize(5);
        for (int id = 1; id <= 5; id++) {
            String nodeLabel = "node_id=\"" + id + "\"";
            assertThat(status).filteredOn(line -> line.contains(nodeLabel)).singleElement()
                    .satisfies(line -> assertThat(line)
                            .contains("application=\"udcf\"")
                            .contains("mode=\"local\"")
                            .endsWith(" 1.0"));
        }
    }

    @Test
    @DisplayName("every distributed_* sample line has a node_id label and no role label")
    void everyDistributedSampleHasNodeId() {
        List<String> distributed = sampleLines.stream().filter(line -> line.startsWith("distributed_")).toList();

        assertThat(distributed).isNotEmpty().allSatisfy(line -> assertThat(line)
                .contains("node_id=\"")
                .doesNotContain("role=\""));
    }

    @Test
    @DisplayName("the event counters appear exactly once each, with node_id 0 and a single _total")
    void eventCountersOnce() {
        assertThat(samples(MetricNames.EVENTS_PUBLISHED_TOTAL)).singleElement()
                .satisfies(line -> assertThat(line).contains("node_id=\"0\""));
        assertThat(samples(MetricNames.EVENT_NOTIFICATIONS_DROPPED_TOTAL)).singleElement()
                .satisfies(line -> assertThat(line).contains("node_id=\"0\""));
        assertThat(sampleLines).noneMatch(line -> line.contains("_total_total"));
    }

    @Test
    @DisplayName("each Exp 2 gauge appears once per node, and the request meters carry node_id 1")
    void exp2MetersCarryNodeIds() {
        for (String gauge : List.of("distributed_active_threads", "distributed_pool_size",
                "distributed_queued_requests", "distributed_queue_remaining_capacity",
                "distributed_request_throughput", "distributed_response_time_p95_millis")) {
            assertThat(samples(gauge)).as(gauge).hasSize(5);
            for (int id = 1; id <= 5; id++) {
                String nodeLabel = "node_id=\"" + id + "\"";
                assertThat(samples(gauge)).as(gauge + " " + nodeLabel)
                        .filteredOn(line -> line.contains(nodeLabel)).hasSize(1);
            }
        }
        assertThat(samples(MetricNames.REQUESTS_TOTAL)).isNotEmpty()
                .allSatisfy(line -> assertThat(line).contains("node_id=\"1\""));
        assertThat(sampleLines).anyMatch(line ->
                line.startsWith("distributed_request_duration_seconds_count{") && line.contains("node_id=\"1\""));
    }
}
