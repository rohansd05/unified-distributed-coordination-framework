package com.udcf.modules.clocksync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ClockSyncPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ClockSyncProperties.class)
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    @DisplayName("application.yml binds clocksync properties with defaults")
    void bindsDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ClockSyncProperties props = context.getBean(ClockSyncProperties.class);
            assertThat(props.pollTimeoutMillis()).isEqualTo(1000L);
            assertThat(props.outlierThresholdMillis()).isEqualTo(500L);
            assertThat(props.nodes()).hasSize(5);
            assertThat(props.configFor(2).initialOffsetMillis()).isEqualTo(40L);
            assertThat(props.configFor(2).driftRateMsPerSec()).isEqualTo(1.5);
        });
    }

    @Test
    @DisplayName("the public profile inherits the clocksync configuration")
    void publicProfileInherits() {
        runner.withPropertyValues("spring.profiles.active=public")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ClockSyncProperties props = context.getBean(ClockSyncProperties.class);
                    assertThat(props.pollTimeoutMillis()).isEqualTo(1000L);
                    assertThat(props.outlierThresholdMillis()).isEqualTo(500L);
                });
    }

    @Test
    @DisplayName("poll timeout below 10 ms fails validation")
    void timeoutBelowMinFails() {
        runner.withPropertyValues("udcf.clocksync.poll-timeout-millis=5")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("pollTimeoutMillis"));
    }
}
