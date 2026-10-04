package com.udcf.web;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeService;
import com.udcf.core.cluster.NodeStatus;
import com.udcf.core.module.ModuleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards every /api/cluster endpoint, including Step 1.3's done-when: a crash through the
 * API reaches a node's services and the event log.
 *
 * <p>Runs in context D ({@link TestModuleConfig}), the only context where nodes are
 * crashed through the API or the cluster is reset, so no other test class loses history
 * it depends on. Every crash is undone in {@code finally}, and each test checks only events
 * it produced.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestModuleConfig.class)
class ClusterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Cluster cluster;

    @Autowired
    private TestModuleConfig.FakeModule fakeModule;

    /** Inline fake endpoint; binds nothing. */
    private static final class ProbeService implements NodeService {
        static final String NAME = "probe";
        private volatile boolean running;

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void crash() {
            running = false;
        }

        @Override
        public void recover() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    @Test
    @DisplayName("GET /api/cluster lists 5 nodes with the default capacities, ports and no roles")
    void getCluster() throws Exception {
        mockMvc.perform(get("/api/cluster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.nodes", hasSize(5)))
                .andExpect(jsonPath("$.nodes[*].id", contains(1, 2, 3, 4, 5)))
                .andExpect(jsonPath("$.nodes[*].capacity.name", contains("FAST", "MEDIUM", "SLOW", "MEDIUM", "FAST")))
                .andExpect(jsonPath("$.nodes[2].ports.election").value(7003))
                .andExpect(jsonPath("$.nodes[2].capacity.threads").value(1))
                .andExpect(jsonPath("$.nodes[*].roles", everyItem(hasSize(0))));
    }

    @Test
    @DisplayName("GET /api/cluster/nodes/{id} returns the node, or 404 with a ProblemDetail")
    void getNode() throws Exception {
        mockMvc.perform(get("/api/cluster/nodes/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.capacity.name").value("SLOW"))
                .andExpect(jsonPath("$.ports.election").value(7003));

        mockMvc.perform(get("/api/cluster/nodes/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Unknown node"))
                .andExpect(jsonPath("$.detail").value("No node with id 99"))
                .andExpect(jsonPath("$.nodeId").value(99));
    }

    @Test
    @DisplayName("done-when: crash through the API reaches the node's services and the event log; 409 on repeats")
    void crashAndRecoverThroughApi() throws Exception {
        ClusterNode node2 = cluster.node(2);
        ProbeService probe = node2.ensureService(ProbeService.NAME, n -> new ProbeService());
        try {
            mockMvc.perform(post("/api/cluster/nodes/2/crash"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(2))
                    .andExpect(jsonPath("$.status").value("CRASHED"))
                    .andExpect(jsonPath("$.runningServices", hasSize(0)));
            assertThat(probe.isRunning()).isFalse();
            mockMvc.perform(get("/api/events").param("module", "cluster").param("node", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].type", hasItem("NODE_CRASHED")));

            mockMvc.perform(post("/api/cluster/nodes/2/crash"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.title").value("Node state conflict"))
                    .andExpect(jsonPath("$.detail").value("Node 2 is already crashed"))
                    .andExpect(jsonPath("$.nodeId").value(2));

            mockMvc.perform(post("/api/cluster/nodes/2/recover"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.runningServices", hasItem(ProbeService.NAME)));
            assertThat(probe.isRunning()).isTrue();

            mockMvc.perform(post("/api/cluster/nodes/2/recover"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Node state conflict"))
                    .andExpect(jsonPath("$.detail").value("Node 2 is already up"))
                    .andExpect(jsonPath("$.nodeId").value(2));
        } finally {
            cluster.recover(2);
        }
    }

    @Test
    @DisplayName("crash and recover of an unknown node return 404 with nodeId")
    void crashAndRecoverUnknownNode() throws Exception {
        mockMvc.perform(post("/api/cluster/nodes/99/crash"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Unknown node"))
                .andExpect(jsonPath("$.nodeId").value(99));
        mockMvc.perform(post("/api/cluster/nodes/99/recover"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.nodeId").value(99));
    }

    @Test
    @DisplayName("a non-numeric node id returns 400")
    void nonNumericNodeId() throws Exception {
        mockMvc.perform(get("/api/cluster/nodes/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("reset returns 204 and leaves exactly [CLUSTER_RESET] with every node up")
    void resetGivesCleanSlate() throws Exception {
        int resetsBefore = fakeModule.resetCount();
        try {
            cluster.crash(3);

            mockMvc.perform(post("/api/cluster/reset"))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/events"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].type").value("CLUSTER_RESET"))
                    .andExpect(jsonPath("$[0].nodeId").value(0))
                    .andExpect(jsonPath("$[0].lamportTime").value(1))
                    .andExpect(jsonPath("$[0].data.size").value(5));
            mockMvc.perform(get("/api/cluster"))
                    .andExpect(jsonPath("$.upCount").value(5))
                    .andExpect(jsonPath("$.nodes[*].status", everyItem(is("UP"))));
            assertThat(fakeModule.resetCount()).isEqualTo(resetsBefore + 1);
        } finally {
            cluster.recover(3);
        }
    }

    @Test
    @DisplayName("reset while a module is busy returns 409 with moduleId and changes nothing")
    void resetWhileBusy() throws Exception {
        fakeModule.setStatus(ModuleStatus.BUSY);
        try {
            cluster.crash(4);

            mockMvc.perform(post("/api/cluster/reset"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.title").value("Module busy"))
                    .andExpect(jsonPath("$.moduleId").value(TestModuleConfig.FakeModule.ID))
                    .andExpect(jsonPath("$.actionInProgress").value("a long-running action"));

            assertThat(cluster.node(4).status()).isEqualTo(NodeStatus.CRASHED);
        } finally {
            fakeModule.setStatus(ModuleStatus.IDLE);
            cluster.recover(4);
        }
    }
}
