package com.udcf.modules.matrix;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The E10c setting as bound from the real YAML files (both profiles), and its validation. */
class MatrixModulePropertiesTest {

    /** Production registers them by scan; the runner needs them explicitly. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({MatrixModuleProperties.class, MatrixProperties.class})
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    @DisplayName("application.yml binds history-size 20; the public profile inherits it; both cover the series sizes")
    void bindsBothProfiles() {
        runner.run(context -> {
            assertThat(context.getBean(MatrixModuleProperties.class)).isEqualTo(new MatrixModuleProperties(20));
            assertThat(context.getBean(MatrixModuleProperties.class).historySize())
                    .isGreaterThanOrEqualTo(context.getBean(MatrixProperties.class).seriesSizes().size());
        });
        runner.withPropertyValues("spring.profiles.active=public").run(context -> {
            assertThat(context.getBean(MatrixModuleProperties.class)).isEqualTo(new MatrixModuleProperties(20));
            assertThat(context.getBean(MatrixModuleProperties.class).historySize())
                    .isGreaterThanOrEqualTo(context.getBean(MatrixProperties.class).seriesSizes().size());
        });
    }

    @Test
    @DisplayName("a value below 1 fails startup, naming the setting")
    void invalidFailsStartup() {
        runner.withPropertyValues("udcf.matrix.history-size=0")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("historySize"));
    }

    @Test
    @DisplayName("the constructor refuses a history size below 1")
    void constructorValidation() {
        assertThatThrownBy(() -> new MatrixModuleProperties(0)).hasMessage("historySize must be >= 1, was 0");
    }
}
