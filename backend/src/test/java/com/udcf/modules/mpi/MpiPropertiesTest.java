package com.udcf.modules.mpi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Guards the Experiment 9 settings as bound from the real application.yml, and their validation. */
class MpiPropertiesTest {

    /** Production registers MpiProperties by scan; the runner needs it explicitly. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MpiProperties.class)
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    @DisplayName("application.yml binds 100000 elements, 10000 ms, 20 collectives, 8 stored payloads and 300-character messages")
    void bindsDefaults() {
        runner.run(context -> assertThat(context.getBean(MpiProperties.class))
                .isEqualTo(new MpiProperties(100_000, 10_000, 20, 8, 300)));
    }

    @Test
    @DisplayName("the public profile allows at most 10000 elements and keeps the rest")
    void publicProfile() {
        runner.withPropertyValues("spring.profiles.active=public")
                .run(context -> assertThat(context.getBean(MpiProperties.class))
                        .isEqualTo(new MpiProperties(10_000, 10_000, 20, 8, 300)));
    }

    @Test
    @DisplayName("any value below 1 fails startup, naming the setting")
    void valuesBelowOneFailStartup() {
        Map<String, String> keys = Map.of(
                "max-payload-elements", "maxPayloadElements",
                "collective-deadline-millis", "collectiveDeadlineMillis",
                "history-size", "historySize",
                "receive-store-size", "receiveStoreSize",
                "max-failure-message-length", "maxFailureMessageLength");
        keys.forEach((key, name) -> runner.withPropertyValues("udcf.mpi." + key + "=0")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining(name)));
    }

    @Test
    @DisplayName("the constructor rejects values below 1 with the setting's name")
    void constructorValidation() {
        assertThatThrownBy(() -> new MpiProperties(0, 1, 1, 1, 1)).hasMessage("maxPayloadElements must be >= 1, was 0");
        assertThatThrownBy(() -> new MpiProperties(1, 0, 1, 1, 1)).hasMessage("collectiveDeadlineMillis must be >= 1, was 0");
        assertThatThrownBy(() -> new MpiProperties(1, 1, 0, 1, 1)).hasMessage("historySize must be >= 1, was 0");
        assertThatThrownBy(() -> new MpiProperties(1, 1, 1, 0, 1)).hasMessage("receiveStoreSize must be >= 1, was 0");
        assertThatThrownBy(() -> new MpiProperties(1, 1, 1, 1, 0)).hasMessage("maxFailureMessageLength must be >= 1, was 0");
    }
}
