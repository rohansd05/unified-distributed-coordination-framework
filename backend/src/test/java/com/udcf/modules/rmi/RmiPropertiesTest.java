package com.udcf.modules.rmi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Guards the Experiment 1 settings as bound from the real application.yml, and their validation. */
class RmiPropertiesTest {

    /** Production registers RmiProperties by scan; the runner needs it explicitly. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RmiProperties.class)
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    @DisplayName("application.yml binds 1000000 rounds, 1024 characters, 50 calls, 3000 ms and 300-character messages")
    void bindsDefaults() {
        runner.run(context -> assertThat(context.getBean(RmiProperties.class))
                .isEqualTo(new RmiProperties(1_000_000, 1024, 50, 3000, 300)));
    }

    @Test
    @DisplayName("the public profile allows at most 100000 hash rounds and keeps the rest")
    void publicProfile() {
        runner.withPropertyValues("spring.profiles.active=public")
                .run(context -> assertThat(context.getBean(RmiProperties.class))
                        .isEqualTo(new RmiProperties(100_000, 1024, 50, 3000, 300)));
    }

    @Test
    @DisplayName("any value below 1 fails startup, naming the setting")
    void valuesBelowOneFailStartup() {
        Map<String, String> keys = Map.of(
                "max-hash-rounds", "maxHashRounds",
                "max-echo-length", "maxEchoLength",
                "history-size", "historySize",
                "call-timeout-millis", "callTimeoutMillis",
                "max-failure-message-length", "maxFailureMessageLength");
        keys.forEach((key, name) -> runner.withPropertyValues("udcf.rmi." + key + "=0")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining(name)));
    }

    @Test
    @DisplayName("the constructor rejects values below 1 with the setting's name")
    void constructorValidation() {
        assertThatThrownBy(() -> new RmiProperties(0, 1, 1, 1, 1)).hasMessage("maxHashRounds must be >= 1, was 0");
        assertThatThrownBy(() -> new RmiProperties(1, 0, 1, 1, 1)).hasMessage("maxEchoLength must be >= 1, was 0");
        assertThatThrownBy(() -> new RmiProperties(1, 1, 0, 1, 1)).hasMessage("historySize must be >= 1, was 0");
        assertThatThrownBy(() -> new RmiProperties(1, 1, 1, 0, 1)).hasMessage("callTimeoutMillis must be >= 1, was 0");
        assertThatThrownBy(() -> new RmiProperties(1, 1, 1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxFailureMessageLength must be >= 1, was 0");
    }
}
