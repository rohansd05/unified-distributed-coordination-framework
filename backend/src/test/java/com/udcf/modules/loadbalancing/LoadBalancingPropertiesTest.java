package com.udcf.modules.loadbalancing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Experiment 6 settings as bound from the real application.yml (the public
 * profile inherits them), and that an invalid value fails startup.
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
    @DisplayName("application.yml binds a 10000 ms request timeout")
    void bindsDefaults() {
        runner.run(context -> assertThat(context.getBean(LoadBalancingProperties.class))
                .isEqualTo(new LoadBalancingProperties(10_000)));
    }

    @Test
    @DisplayName("the public profile inherits the 10000 ms request timeout")
    void publicProfileInherits() {
        runner.withPropertyValues("spring.profiles.active=public")
                .run(context -> assertThat(context.getBean(LoadBalancingProperties.class).requestTimeoutMillis())
                        .isEqualTo(10_000));
    }

    @Test
    @DisplayName("a request timeout of 0 fails startup")
    void zeroTimeoutFails() {
        runner.withPropertyValues("udcf.loadbalancing.request-timeout-millis=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("requestTimeoutMillis"));
    }
}
