package com.udcf.modules.loadbalancing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Experiment 6 settings as bound from the real application.yml and
 * application-public.yml, and that invalid values fail startup.
 */
class LoadBalancingPropertiesTest {

    /** Production registers LoadBalancingProperties by scan; the runner needs it explicitly. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LoadBalancingProperties.class)
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    @DisplayName("application.yml binds timeout 10000, defaults 60 / 400 / 12, limits 1000 / 5000 / 50 and a 500000 total-work cap")
    void bindsDefaults() {
        runner.run(context -> assertThat(context.getBean(LoadBalancingProperties.class))
                .isEqualTo(new LoadBalancingProperties(10_000,
                        new LoadBalancingProperties.Defaults(60, 400, 12),
                        new LoadBalancingProperties.Limits(1000, 5000, 50, 500_000))));
    }

    @Test
    @DisplayName("the public profile keeps timeout and defaults and shrinks the limits to 200 / 500 / 24 and 120000")
    void publicProfile() {
        runner.withPropertyValues("spring.profiles.active=public")
                .run(context -> assertThat(context.getBean(LoadBalancingProperties.class))
                        .isEqualTo(new LoadBalancingProperties(10_000,
                                new LoadBalancingProperties.Defaults(60, 400, 12),
                                new LoadBalancingProperties.Limits(200, 500, 24, 120_000))));
    }

    @Test
    @DisplayName("a request timeout of 0 fails startup")
    void zeroTimeoutFails() {
        runner.withPropertyValues("udcf.loadbalancing.request-timeout-millis=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("requestTimeoutMillis"));
    }

    @Test
    @DisplayName("defaults above a limit fail startup")
    void defaultsAboveLimitFail() {
        runner.withPropertyValues("udcf.loadbalancing.defaults.concurrency=51")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("defaultsWithinLimits"));
    }

    @Test
    @DisplayName("a total-work cap too small for a default comparison fails startup")
    void capTooSmallForDefaultComparisonFails() {
        runner.withPropertyValues("udcf.loadbalancing.limits.max-total-work=119999")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("defaultsWithinLimits"));
    }

    @Test
    @DisplayName("work units above the Exp 2 payload limit of 5000 fail startup")
    void workUnitsAboveExp2LimitFail() {
        runner.withPropertyValues("udcf.loadbalancing.limits.max-work-units=5001")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("maxWorkUnits"));
    }

    @Test
    @DisplayName("a total-work cap of 0 fails startup; missing blocks are reported by @NotNull, not by the cross-check")
    void zeroCapFails() {
        assertThat(new LoadBalancingProperties(1, null, null).isDefaultsWithinLimits()).isTrue();
        runner.withPropertyValues("udcf.loadbalancing.limits.max-total-work=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("maxTotalWork"));
    }
}
