package com.udcf;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.events.EventProperties;
import com.udcf.modules.multithreading.MultithreadingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the public lite profile (docs/HANDOFF.md 6.10): a smaller cluster, buffer and
 * thread pool, and no metrics endpoints.
 *
 * <p>{@code @AutoConfigureObservability} keeps the Prometheus exporter on, so the 404 on
 * {@code /actuator/prometheus} proves the endpoint is not exposed rather than merely
 * absent in tests. Runs in its own context.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ActiveProfiles("public")
class PublicProfileTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Cluster cluster;

    @Autowired
    private EventProperties eventProperties;

    @Autowired
    private MultithreadingProperties multithreadingProperties;

    @Test
    @DisplayName("the cluster has 3 nodes, FAST, MEDIUM, SLOW, and the event buffer holds 1500")
    void smallerClusterAndBuffer() {
        assertThat(cluster.size()).isEqualTo(3);
        assertThat(cluster.nodes()).extracting(ClusterNode::capacity)
                .containsExactly(NodeCapacity.FAST, NodeCapacity.MEDIUM, NodeCapacity.SLOW);
        assertThat(eventProperties.bufferSize()).isEqualTo(1500);
    }

    @Test
    @DisplayName("each node's Exp 2 queue holds 100 (threads come from the node's capacity)")
    void smallerThreadPool() {
        assertThat(multithreadingProperties.queueCapacity()).isEqualTo(100);
    }

    @Test
    @DisplayName("GET /api/system/info reports mode public and cluster size 3")
    void infoReportsPublic() throws Exception {
        mockMvc.perform(get("/api/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("public"))
                .andExpect(jsonPath("$.clusterSize").value(3));
    }

    @Test
    @DisplayName("GET /actuator/health is 200 with status UP and no components or details in body")
    void healthExposedWithoutDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    @DisplayName("GET /actuator/prometheus is 404")
    void prometheusNotExposed() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
    }
}
