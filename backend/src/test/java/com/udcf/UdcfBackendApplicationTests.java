package com.udcf;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.module.ModuleRegistry;
import com.udcf.modules.multithreading.MultithreadingModule;
import com.udcf.modules.multithreading.MultithreadingProperties;
import com.udcf.modules.multithreading.RequestsNodeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the wiring: the Exp 2 module, its settings and its metrics come up with the shared
 * cluster, and the pre-E2c single-node wiring (one executor bean for udcf.node.id) is gone.
 */
@SpringBootTest
class UdcfBackendApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ModuleRegistry modules;

    @Autowired
    private MultithreadingProperties multithreadingProperties;

    @Autowired
    private Cluster cluster;

    @Test
    @DisplayName("context loads with the multithreading module registered and its settings from application.yml")
    void contextLoads() {
        assertThat(modules.get("multithreading")).isInstanceOf(MultithreadingModule.class);
        assertThat(multithreadingProperties.queueCapacity()).isEqualTo(200);
        assertThat(multithreadingProperties.backpressure().extraRequests()).isEqualTo(50);
    }

    @Test
    @DisplayName("no executor bean and no node identity in configuration: each node builds its own executor on first use")
    void legacyWiringIsGone() {
        assertThat(context.getBeansOfType(ThreadPoolExecutor.class)).isEmpty();
        assertThat(context.getEnvironment().getProperty("udcf.node.id")).isNull();
        assertThat(context.getEnvironment().getProperty("udcf.threadpool.core-pool-size")).isNull();
        assertThat(cluster.nodes()).allSatisfy((ClusterNode node) ->
                assertThat(RequestsNodeService.find(node)).as("services start lazily").isEmpty());
    }
}
