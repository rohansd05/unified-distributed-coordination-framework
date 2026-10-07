package com.udcf.core;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.events.ClusterEvent;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.module.ExperimentModule;
import com.udcf.core.module.ModuleRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the core wiring under the real application.yml: the shared cluster, the module
 * registry and the event bus come up together.
 */
@SpringBootTest
class CoreContextTest {

    @Autowired
    private Cluster cluster;

    @Autowired
    private ModuleRegistry moduleRegistry;

    @Autowired
    private ClusterEventBus eventBus;

    @Test
    @DisplayName("the application starts with the configured cluster, the registered modules and CLUSTER_STARTED")
    void coreComesUp() {
        assertThat(cluster.size()).isEqualTo(5);
        assertThat(cluster.nodes()).extracting(ClusterNode::capacity).containsExactly(
                NodeCapacity.FAST, NodeCapacity.MEDIUM, NodeCapacity.SLOW,
                NodeCapacity.MEDIUM, NodeCapacity.FAST);
        assertThat(cluster.node(3).ports().election()).isEqualTo(7003);
        // "Contains", not an exact list: other tracks' modules join the registry later (E2c).
        assertThat(moduleRegistry.modules()).extracting(ExperimentModule::id).contains("multithreading");
        assertThat(moduleRegistry.modules()).extracting(ExperimentModule::labNumber).isSorted();
        assertThat(moduleRegistry.get("multithreading").labNumber()).isEqualTo(2);
        assertThat(eventBus.query("cluster", 0, 100))
                .extracting(ClusterEvent::type)
                .contains("CLUSTER_STARTED");
    }
}
