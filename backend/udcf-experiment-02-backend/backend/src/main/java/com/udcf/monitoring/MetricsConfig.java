package com.udcf.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stamps every metric emitted by this JVM with the node it came from.
 *
 * <p>Prometheus scrapes four targets that all expose identically named metrics. Without
 * these common tags the series collide and no Grafana panel can break down by node —
 * so this is applied once, globally, rather than per meter.</p>
 *
 * <p>No dedicated test file: a single declarative customizer. Its effect is asserted in
 * ThreadPoolMetricsTest, which checks the tags land on registered meters.</p>
 */
@Configuration
public class MetricsConfig {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> commonNodeTags(
            @Value("${udcf.node.id:1}") String nodeId,
            @Value("${udcf.node.role:WORKER}") String role) {

        return registry -> registry.config().meterFilter(
                MeterFilter.commonTags(io.micrometer.core.instrument.Tags.of(
                        "node_id", nodeId,
                        "role", role
                ))
        );
    }
}
