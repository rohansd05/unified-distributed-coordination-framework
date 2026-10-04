package com.udcf.core.events;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the cluster event bus and the application's wall clock.
 *
 * <p>No dedicated behaviour of its own; EventConfigTest checks that the beans come up from
 * configuration and that the bus is closed with the context.</p>
 */
@Configuration
public class EventConfig {

    /** Wall clock for display timestamps; a bean so tests can substitute a fixed one. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Closed on context shutdown so the dispatcher thread stops. */
    @Bean(destroyMethod = "close")
    public ClusterEventBus clusterEventBus(EventProperties properties, Clock clock) {
        return new ClusterEventBus(properties, clock);
    }
}
