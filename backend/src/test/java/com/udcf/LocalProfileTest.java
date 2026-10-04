package com.udcf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the default configuration: the local profile is the default, the backend listens
 * on 8080 unless PORT says otherwise, and the local profile exposes metrics.
 */
class LocalProfileTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    @DisplayName("local is the default profile and exposes health, info, metrics and prometheus")
    void localIsDefault() {
        runner.run(context -> {
            assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
            assertThat(context.getEnvironment().getDefaultProfiles()).containsExactly("local");
            assertThat(context.getEnvironment().getProperty("management.endpoints.web.exposure.include"))
                    .isEqualTo("health,info,metrics,prometheus");
            assertThat(context.getEnvironment().getProperty("udcf.system.mode")).isEqualTo("local");
        });
    }

    @Test
    @DisplayName("server.port resolves to 8080 by default")
    void portDefaultsTo8080() {
        assumeTrue(System.getenv("PORT") == null, "PORT is set in this environment, so the default is not in effect");

        runner.run(context -> assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("8080"));
    }

    @Test
    @DisplayName("a PORT property overrides the port")
    void portPropertyOverrides() {
        runner.withPropertyValues("PORT=9999")
                .run(context -> assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("9999"));
    }
}
