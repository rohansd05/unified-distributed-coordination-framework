package com.udcf.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards system identity binding: the lowercase mode binds to the enum, missing values fail
 * startup, and the build fills the version from the pom.
 */
class UdcfSystemPropertiesTest {

    /** Production registers UdcfSystemProperties by scan; the runner needs it explicitly. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(UdcfSystemProperties.class)
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    @DisplayName("a lowercase mode binds to the enum")
    void bindsLowercaseMode() {
        runner.withPropertyValues("udcf.system.version=1.2.3", "udcf.system.mode=public")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    UdcfSystemProperties properties = context.getBean(UdcfSystemProperties.class);
                    assertThat(properties.version()).isEqualTo("1.2.3");
                    assertThat(properties.mode()).isEqualTo(RunMode.PUBLIC);
                });
    }

    @Test
    @DisplayName("a blank version fails startup")
    void blankVersionFails() {
        runner.withPropertyValues("udcf.system.version= ", "udcf.system.mode=local")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("version"));
    }

    @Test
    @DisplayName("a missing mode fails startup")
    void missingModeFails() {
        runner.withPropertyValues("udcf.system.version=1.2.3")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("mode"));
    }

    @Test
    @DisplayName("the built application.yml carries the pom version and mode local")
    void filteredYamlCarriesPomVersion() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    UdcfSystemProperties properties = context.getBean(UdcfSystemProperties.class);
                    assertThat(properties.version()).isEqualTo(PomVersion.read()).doesNotContain("@");
                    assertThat(properties.mode()).isEqualTo(RunMode.LOCAL);
                });
    }
}
