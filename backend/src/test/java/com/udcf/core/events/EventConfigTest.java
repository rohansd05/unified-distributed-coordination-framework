package com.udcf.core.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the wiring: properties bind from {@code udcf.events.*}, invalid sizes fail
 * startup, and the bus is created and closed with the context.
 */
class EventConfigTest {

    /** Production registers EventProperties by scan; the runner needs it explicitly. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EventProperties.class)
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesRegistration.class, EventConfig.class);

    @Test
    @DisplayName("binds buffer and dispatch queue sizes from udcf.events.*")
    void bindsProperties() {
        runner.withPropertyValues("udcf.events.buffer-size=1500", "udcf.events.dispatch-queue-size=64")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EventProperties properties = context.getBean(EventProperties.class);
                    assertThat(properties.bufferSize()).isEqualTo(1500);
                    assertThat(properties.dispatchQueueSize()).isEqualTo(64);
                });
    }

    @Test
    @DisplayName("a buffer size of 0 fails context startup")
    void zeroBufferSizeFails() {
        runner.withPropertyValues("udcf.events.buffer-size=0", "udcf.events.dispatch-queue-size=64")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("bufferSize"));
    }

    @Test
    @DisplayName("a dispatch queue size of 0 fails context startup")
    void zeroDispatchQueueSizeFails() {
        runner.withPropertyValues("udcf.events.buffer-size=10", "udcf.events.dispatch-queue-size=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("dispatchQueueSize"));
    }

    @Test
    @DisplayName("provides the bus and clock beans and closes the bus with the context")
    void providesBusAndClosesIt() {
        AtomicReference<ClusterEventBus> captured = new AtomicReference<>();
        runner.withPropertyValues("udcf.events.buffer-size=10", "udcf.events.dispatch-queue-size=4")
                .run(context -> {
                    assertThat(context).hasSingleBean(ClusterEventBus.class).hasSingleBean(Clock.class);
                    captured.set(context.getBean(ClusterEventBus.class));
                    assertThat(captured.get().isDispatcherAlive()).isTrue();
                });

        assertThat(captured.get().isDispatcherAlive()).isFalse();
    }
}
