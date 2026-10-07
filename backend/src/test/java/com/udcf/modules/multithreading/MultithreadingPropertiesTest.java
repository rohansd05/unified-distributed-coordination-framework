package com.udcf.modules.multithreading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Experiment 2 settings as bound from the real application.yml and
 * application-public.yml, and that an invalid value fails startup.
 */
class MultithreadingPropertiesTest {

    /** Production registers MultithreadingProperties by scan; the runner needs it explicitly. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MultithreadingProperties.class)
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    @DisplayName("application.yml binds queue 200, keep-alive 60, prefix udcf-worker-, window 30, history 500")
    void bindsDefaults() {
        runner.run(context -> assertThat(context.getBean(MultithreadingProperties.class))
                .isEqualTo(new MultithreadingProperties(200, 60, "udcf-worker-", 30, 500)));
    }

    @Test
    @DisplayName("the public profile shrinks the queue to 100")
    void publicProfileShrinksQueue() {
        runner.withPropertyValues("spring.profiles.active=public")
                .run(context -> assertThat(context.getBean(MultithreadingProperties.class).queueCapacity())
                        .isEqualTo(100));
    }

    @Test
    @DisplayName("a queue capacity of 0 fails startup")
    void zeroQueueFails() {
        runner.withPropertyValues("udcf.multithreading.queue-capacity=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("queueCapacity"));
    }
}
