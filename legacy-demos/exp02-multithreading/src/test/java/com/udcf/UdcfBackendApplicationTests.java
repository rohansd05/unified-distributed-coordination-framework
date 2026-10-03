package com.udcf;

import com.udcf.threadpool.RequestProcessingService;
import com.udcf.threadpool.ThreadPoolStatsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the wiring: the executor bean, the services and the metrics binding must all
 * come up together under the default profile.
 */
@SpringBootTest(properties = {
        "udcf.node.id=1",
        "udcf.threadpool.core-pool-size=2",
        "udcf.threadpool.max-pool-size=4",
        "udcf.threadpool.queue-capacity=20"
})
class UdcfBackendApplicationTests {

    @Autowired
    private ThreadPoolExecutor executor;

    @Autowired
    private RequestProcessingService processingService;

    @Autowired
    private ThreadPoolStatsService statsService;

    @Test
    @DisplayName("context loads with the executor configured from properties")
    void contextLoads() {
        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaximumPoolSize()).isEqualTo(4);
        assertThat(executor.getQueue().remainingCapacity()).isEqualTo(20);
    }

    @Test
    @DisplayName("the node identity reaches the services from configuration, not code")
    void nodeIdentityComesFromConfiguration() {
        assertThat(processingService.getNodeId()).isEqualTo(1);
        assertThat(statsService.snapshot().nodeId()).isEqualTo(1);
    }
}
