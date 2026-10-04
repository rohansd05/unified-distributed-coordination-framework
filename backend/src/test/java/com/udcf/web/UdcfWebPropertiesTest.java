package com.udcf.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards that allowed origins come only from configuration, bind from a comma-separated
 * environment value, and that malformed origins fail startup instead of silently never
 * matching.
 */
class UdcfWebPropertiesTest {

    /** Production registers UdcfWebProperties by scan; the runner needs it explicitly. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(UdcfWebProperties.class)
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    @DisplayName("a comma-separated value binds to a list")
    void bindsCommaSeparatedList() {
        runner.withPropertyValues("udcf.web.allowed-origins=http://localhost:5173,https://*.vercel.app")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(UdcfWebProperties.class).allowedOrigins())
                            .containsExactly("http://localhost:5173", "https://*.vercel.app");
                });
    }

    @Test
    @DisplayName("whitespace around entries is trimmed")
    void trimsWhitespace() {
        runner.withPropertyValues("udcf.web.allowed-origins= http://a.example , http://b.example ")
                .run(context -> assertThat(context.getBean(UdcfWebProperties.class).allowedOrigins())
                        .containsExactly("http://a.example", "http://b.example"));
    }

    @Test
    @DisplayName("a blank entry fails startup")
    void blankEntryFails() {
        runner.withPropertyValues("udcf.web.allowed-origins=http://a.example, ,http://b.example")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("blank entry"));
    }

    @Test
    @DisplayName("an empty value fails startup")
    void emptyValueFails() {
        runner.withPropertyValues("udcf.web.allowed-origins=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("allowedOrigins"));
    }

    @Test
    @DisplayName("an entry ending in a slash fails startup with a clear message")
    void trailingSlashFails() {
        runner.withPropertyValues("udcf.web.allowed-origins=http://localhost:5173/")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("must not end with '/'"));
    }

    @Test
    @DisplayName("the application.yml default allows localhost:5173 and 127.0.0.1:5173")
    void yamlDefault() {
        assumeTrue(System.getenv("UDCF_ALLOWED_ORIGINS") == null,
                "UDCF_ALLOWED_ORIGINS is set in this environment, so the default is not in effect");

        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> assertThat(context.getBean(UdcfWebProperties.class).allowedOrigins())
                        .containsExactly("http://localhost:5173", "http://127.0.0.1:5173"));
    }
}
