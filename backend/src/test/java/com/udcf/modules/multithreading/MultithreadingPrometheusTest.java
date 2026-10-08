package com.udcf.modules.multithreading;

import com.udcf.core.metrics.MetricNames;
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
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What Prometheus actually scrapes for Experiment 2 across a node's life: before first use,
 * during work, after a crash and after a recovery. Each of the six gauges must appear exactly
 * once per node_id every time (bound once, never re-registered), NaN while node 1 has no
 * executor and a number while it has one.
 *
 * <p>{@code @AutoConfigureObservability} turns the Prometheus exporter on, and the test-only
 * requests base (21840; ports below 32768, outside the Linux and Windows ephemeral ranges)
 * gives this class its own context, since it crashes a node.</p>
 */
@SpringBootTest(properties = "udcf.cluster.ports.requests-base=21840")
@AutoConfigureMockMvc
@AutoConfigureObservability
class MultithreadingPrometheusTest {

    private static final List<String> GAUGES = List.of(MetricNames.ACTIVE_THREADS, MetricNames.POOL_SIZE,
            MetricNames.QUEUED_REQUESTS, MetricNames.QUEUE_REMAINING_CAPACITY, MetricNames.REQUEST_THROUGHPUT,
            MetricNames.RESPONSE_TIME_P95_MILLIS);
    private static final Pattern NODE_ID = Pattern.compile("node_id=\"(\\d+)\"");

    @Autowired
    private MockMvc mockMvc;

    /** For one gauge: node_id to the values scraped for it (more than one value would be a duplicate). */
    private Map<String, List<String>> scrape(String gauge) throws Exception {
        String body = mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, List<String>> byNode = new TreeMap<>();
        Arrays.stream(body.split("\n"))
                .filter(line -> line.startsWith(gauge + "{"))
                .forEach(line -> {
                    Matcher matcher = NODE_ID.matcher(line);
                    assertThat(matcher.find()).as("node_id on %s", line).isTrue();
                    byNode.computeIfAbsent(matcher.group(1), id -> new java.util.ArrayList<>())
                            .add(line.substring(line.lastIndexOf(' ') + 1));
                });
        return byNode;
    }

    /** Every gauge appears once for each of nodes 1..5, and returns node 1's value per gauge. */
    private Map<String, String> assertOncePerNode(String phase) throws Exception {
        Map<String, String> nodeOne = new TreeMap<>();
        for (String gauge : GAUGES) {
            Map<String, List<String>> byNode = scrape(gauge);
            assertThat(byNode.keySet()).as("%s %s node ids", phase, gauge).containsExactly("1", "2", "3", "4", "5");
            assertThat(byNode.values()).as("%s %s once per node", phase, gauge).allMatch(values -> values.size() == 1);
            nodeOne.put(gauge, byNode.get("1").get(0));
        }
        return nodeOne;
    }

    @Test
    @DisplayName("the six gauges appear once per node before use, during work, after a crash and after recovery")
    void gaugesThroughTheNodeLifecycle() throws Exception {
        assertThat(assertOncePerNode("before first use").values()).allMatch("NaN"::equals);

        mockMvc.perform(post("/api/modules/multithreading/nodes/1/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":6,\"type\":\"IO_SIMULATED\",\"payloadSize\":5000}"))
                .andExpect(status().isAccepted());
        await().atMost(5, TimeUnit.SECONDS).until(() -> "4.0".equals(scrape(MetricNames.ACTIVE_THREADS).get("1").get(0)));
        Map<String, String> during = assertOncePerNode("during work");
        assertThat(during.get(MetricNames.ACTIVE_THREADS)).isEqualTo("4.0");
        assertThat(during.get(MetricNames.QUEUED_REQUESTS)).isEqualTo("2.0");

        mockMvc.perform(post("/api/cluster/nodes/1/crash")).andExpect(status().isOk());
        assertThat(assertOncePerNode("after a crash").values()).allMatch("NaN"::equals);

        mockMvc.perform(post("/api/cluster/nodes/1/recover")).andExpect(status().isOk());
        Map<String, String> recovered = assertOncePerNode("after recovery");
        assertThat(recovered.get(MetricNames.ACTIVE_THREADS)).isEqualTo("0.0");
        assertThat(recovered.get(MetricNames.QUEUE_REMAINING_CAPACITY)).isEqualTo("200.0");
        assertThat(recovered.values()).noneMatch("NaN"::equals);
    }
}
