package com.udcf.modules.matrix;

import com.udcf.modules.mpi.MpiProperties;
import com.udcf.modules.rmi.RmiProperties;
import com.udcf.modules.rmi.RmiTransportProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The E10b settings as bound from the real YAML files (both profiles), their validation, and their fit with
 * udcf.rmi (compute below call timeout) and the budget.
 */
class MatrixRunPropertiesTest {

    /** Production registers them by scan; the runner needs them explicitly. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({MatrixRunProperties.class, MatrixProperties.class, MpiProperties.class, RmiProperties.class,
            RmiTransportProperties.class})
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    @DisplayName("application.yml binds warm-up 1, compute timeout 2000, result store 4; compute < call; worst case 26001 ms; E10a keys unchanged")
    void bindsDefaults() {
        runner.run(context -> {
            MatrixRunProperties run = context.getBean(MatrixRunProperties.class);
            assertThat(run).isEqualTo(new MatrixRunProperties(1, 2000, 4));
            RmiProperties rmi = context.getBean(RmiProperties.class);
            MatrixEndpointExport.requireComputeBelowCall(run, rmi);
            assertThat(run.computeTimeoutMillis()).isLessThan(rmi.callTimeoutMillis());
            assertThat(MatrixRunBudget.of(rmi, context.getBean(RmiTransportProperties.class), context.getBean(MpiProperties.class), run)
                    .worstCaseRunMillis()).isEqualTo(26_001);
            assertThat(context.getBean(MatrixProperties.class)).isEqualTo(new MatrixProperties(2, List.of(50, 100, 200, 316)));
        });
    }

    @Test
    @DisplayName("the public profile inherits the same values; compute < call there too; worst case 26001 ms")
    void publicProfile() {
        runner.withPropertyValues("spring.profiles.active=public").run(context -> {
            MatrixRunProperties run = context.getBean(MatrixRunProperties.class);
            assertThat(run).isEqualTo(new MatrixRunProperties(1, 2000, 4));
            RmiProperties rmi = context.getBean(RmiProperties.class);
            MatrixEndpointExport.requireComputeBelowCall(run, rmi);
            assertThat(run.computeTimeoutMillis()).isLessThan(rmi.callTimeoutMillis());
            assertThat(MatrixRunBudget.of(rmi, context.getBean(RmiTransportProperties.class), context.getBean(MpiProperties.class), run)
                    .worstCaseRunMillis()).isEqualTo(26_001);
        });
    }

    @Test
    @DisplayName("an invalid value fails startup, naming the setting")
    void invalidFailsStartup() {
        runner.withPropertyValues("udcf.matrix.warmup-products=-1")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("warmupProducts"));
        runner.withPropertyValues("udcf.matrix.compute-timeout-millis=0")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("computeTimeoutMillis"));
        runner.withPropertyValues("udcf.matrix.result-store-size=0")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("resultStoreSize"));
    }

    @Test
    @DisplayName("the constructor refuses a missing or negative warm-up, a compute timeout or store size below 1")
    void constructorValidation() {
        assertThatThrownBy(() -> new MatrixRunProperties(null, 2000, 4)).hasMessage("warmupProducts must not be null");
        assertThatThrownBy(() -> new MatrixRunProperties(-1, 2000, 4)).hasMessage("warmupProducts must be >= 0, was -1");
        assertThatThrownBy(() -> new MatrixRunProperties(0, 0, 4)).hasMessage("computeTimeoutMillis must be >= 1, was 0");
        assertThatThrownBy(() -> new MatrixRunProperties(0, 2000, 0)).hasMessage("resultStoreSize must be >= 1, was 0");
    }
}
