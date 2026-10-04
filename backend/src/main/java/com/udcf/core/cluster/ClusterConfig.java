package com.udcf.core.cluster;

import com.udcf.core.events.ClusterEventBus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the shared cluster.
 *
 * <p>No dedicated test: declarative wiring. CoreContextTest checks the bean comes up from
 * configuration.</p>
 */
@Configuration
public class ClusterConfig {

    /** Closed on context shutdown so every node's services stop. */
    @Bean(destroyMethod = "close")
    public Cluster cluster(ClusterProperties properties, ClusterEventBus bus) {
        return new Cluster(properties, bus);
    }
}
