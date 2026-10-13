package com.udcf.modules.matrix;

import com.udcf.modules.mpi.MpiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Guards the Experiment 10 settings as bound from the real YAML files, their validation and their fit with udcf.mpi. */
class MatrixPropertiesTest {

    /** Production registers both by scan; the runner needs them explicitly. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({MatrixProperties.class, MpiProperties.class})
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    @DisplayName("application.yml binds min-size 2 and sizes 50, 100, 200, 316, all within the maximum 316")
    void bindsDefaults() {
        runner.run(context -> {
            assertThat(context.getBean(MatrixProperties.class)).isEqualTo(new MatrixProperties(2, List.of(50, 100, 200, 316)));
            MatrixLimits limits = new MatrixLimits(context.getBean(MatrixProperties.class), context.getBean(MpiProperties.class));
            assertThat(limits.maxSize()).isEqualTo(316);
        });
    }

    @Test
    @DisplayName("the public profile binds sizes 25, 50, 100, all within the maximum 100, and keeps min-size 2")
    void publicProfile() {
        runner.withPropertyValues("spring.profiles.active=public").run(context -> {
            assertThat(context.getBean(MatrixProperties.class)).isEqualTo(new MatrixProperties(2, List.of(25, 50, 100)));
            MatrixLimits limits = new MatrixLimits(context.getBean(MatrixProperties.class), context.getBean(MpiProperties.class));
            assertThat(limits.maxSize()).isEqualTo(100);
        });
    }

    @Test
    @DisplayName("an invalid value fails startup, naming the setting")
    void invalidFailsStartup() {
        runner.withPropertyValues("udcf.matrix.min-size=0")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("minSize"));
        runner.withPropertyValues("udcf.matrix.series-sizes=50,100")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("seriesSizes"));
    }

    @Test
    @DisplayName("the constructor refuses a minimum below 1, fewer than three sizes, unordered sizes and sizes below the minimum")
    void constructorValidation() {
        assertThatThrownBy(() -> new MatrixProperties(0, List.of(1, 2, 3))).hasMessage("minSize must be >= 1, was 0");
        assertThatThrownBy(() -> new MatrixProperties(2, null)).hasMessage("seriesSizes must have at least 3 sizes, was null");
        assertThatThrownBy(() -> new MatrixProperties(2, List.of(5, 6))).hasMessage("seriesSizes must have at least 3 sizes, was [5, 6]");
        assertThatThrownBy(() -> new MatrixProperties(2, List.of(5, 5, 6))).hasMessage("seriesSizes must be strictly ascending, was [5, 5, 6]");
        assertThatThrownBy(() -> new MatrixProperties(2, List.of(1, 5, 6))).hasMessage("seriesSizes must each be >= minSize 2, was [1, 5, 6]");
    }
}
