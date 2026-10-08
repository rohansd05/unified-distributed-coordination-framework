package com.udcf.modules.replication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Experiment 5 settings as bound from the real application.yml, and that invalid
 * values fail startup.
 */
class ReplicationPropertiesTest {

    /** Production registers ReplicationProperties by scan; the runner needs it explicitly. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReplicationProperties.class)
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    @DisplayName("application.yml binds a 450 ms simulated async delay, a 1500 ms timeout and batches of 200")
    void bindsDefaults() {
        runner.run(context -> assertThat(context.getBean(ReplicationProperties.class))
                .isEqualTo(new ReplicationProperties(450, 1500, 200)));
    }

    @Test
    @DisplayName("the public profile keeps the same replication settings")
    void publicProfileKeepsDefaults() {
        runner.withPropertyValues("spring.profiles.active=public")
                .run(context -> assertThat(context.getBean(ReplicationProperties.class))
                        .isEqualTo(new ReplicationProperties(450, 1500, 200)));
    }

    @Test
    @DisplayName("a batch size of 0 or above 1000 fails startup")
    void batchSizeOutOfRangeFails() {
        runner.withPropertyValues("udcf.replication.batch-size=0")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("batchSize"));
        runner.withPropertyValues("udcf.replication.batch-size=1001")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("batchSize"));
        runner.withPropertyValues("udcf.replication.batch-size=1000")
                .run(context -> assertThat(context.getBean(ReplicationProperties.class).batchSize()).isEqualTo(1000));
    }

    @Test
    @DisplayName("a negative async delay or a timeout of 0 fails startup")
    void invalidDelayOrTimeoutFails() {
        runner.withPropertyValues("udcf.replication.async-delay-millis=-1")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("asyncDelayMillis"));
        runner.withPropertyValues("udcf.replication.timeout-millis=0")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("timeoutMillis"));
    }
}
