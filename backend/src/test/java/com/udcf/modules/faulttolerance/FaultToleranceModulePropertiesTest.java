package com.udcf.modules.faulttolerance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The E8c stream settings as bound from the real application.yml and application-public.yml,
 * beside the unchanged E8b settings on the same prefix; invalid values fail startup.
 */
class FaultToleranceModulePropertiesTest {

    /** Production registers both by scan; the runner needs them explicitly. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({FaultToleranceModuleProperties.class, FaultToleranceProperties.class})
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    @DisplayName("application.yml binds rate 4 (max 10) and a budget of 400 updates; the E8b settings bind unchanged")
    void bindsLocal() {
        runner.run(context -> {
            assertThat(context.getBean(FaultToleranceModuleProperties.class).stream())
                    .isEqualTo(new FaultToleranceModuleProperties.Stream(4, 10, 400));
            assertThat(context.getBean(FaultToleranceProperties.class)).isEqualTo(new FaultToleranceProperties(
                    new FaultToleranceProperties.Client(50, 250), new FaultToleranceProperties.RoleQuerySettings(1000, 5),
                    new FaultToleranceProperties.Promotion(1000), 10));
        });
    }

    @Test
    @DisplayName("the public profile keeps rate 4 and lowers the max rate to 5 and the budget to 200")
    void bindsPublic() {
        runner.withPropertyValues("spring.profiles.active=public")
                .run(context -> assertThat(context.getBean(FaultToleranceModuleProperties.class).stream())
                        .isEqualTo(new FaultToleranceModuleProperties.Stream(4, 5, 200)));
    }

    @Test
    @DisplayName("a default rate above the max rate fails startup")
    void defaultAboveMaxFails() {
        runner.withPropertyValues("udcf.faulttolerance.stream.default-rate-per-second=11")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("maxRatePerSecond"));
    }

    @Test
    @DisplayName("a budget or a rate of 0 fails startup")
    void zeroFails() {
        runner.withPropertyValues("udcf.faulttolerance.stream.max-updates=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("udcf.faulttolerance.stream.default-rate-per-second=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
