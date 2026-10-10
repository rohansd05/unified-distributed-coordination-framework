package com.udcf.modules.rmi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Guards the Experiment 1 transport settings as bound from the real application.yml, and their validation. */
class RmiTransportPropertiesTest {

    /** Production registers the records by scan; the runner needs them explicitly. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({RmiTransportProperties.class, RmiProperties.class})
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesRegistration.class);

    private static final RmiTransportProperties EXPECTED = new RmiTransportProperties(1000, 50,
            new RmiTransportProperties.SerialFilter(5, 20, 16384, 16));

    @Test
    @DisplayName("application.yml binds 1000 ms, backlog 50 and the filter limits 5, 20, 16384, 16, next to RmiProperties")
    void bindsDefaults() {
        runner.run(context -> {
            assertThat(context.getBean(RmiTransportProperties.class)).isEqualTo(EXPECTED).isEqualTo(RmiTestSupport.TRANSPORT);
            assertThat(context.getBean(RmiProperties.class)).isEqualTo(RmiTestSupport.PROPERTIES);
        });
    }

    @Test
    @DisplayName("the public profile keeps the same transport settings")
    void publicProfile() {
        runner.withPropertyValues("spring.profiles.active=public")
                .run(context -> assertThat(context.getBean(RmiTransportProperties.class)).isEqualTo(EXPECTED));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "connect-timeout-millis, udcf.rmi, connectTimeoutMillis",
            "listen-backlog, udcf.rmi, listenBacklog",
            "serial-filter.max-depth, udcf.rmi.serial-filter, maxDepth",
            "serial-filter.max-refs, udcf.rmi.serial-filter, maxRefs",
            "serial-filter.max-bytes, udcf.rmi.serial-filter, maxBytes",
            "serial-filter.max-array, udcf.rmi.serial-filter, maxArray"})
    @DisplayName("a value below 1, nested ones included, fails startup naming the block and the setting")
    void valuesBelowOneFailStartup(String key, String block, String name) {
        runner.withPropertyValues("udcf.rmi." + key + "=0")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("'" + block + "'")
                        .hasStackTraceContaining(name + " must be >= 1, was 0"));
    }

    @Test
    @DisplayName("the constructors reject values below 1 with the setting's name, and a missing filter block")
    void constructorValidation() {
        RmiTransportProperties.SerialFilter filter = EXPECTED.serialFilter();
        assertThatThrownBy(() -> new RmiTransportProperties(0, 1, filter)).hasMessage("connectTimeoutMillis must be >= 1, was 0");
        assertThatThrownBy(() -> new RmiTransportProperties(1, 0, filter)).hasMessage("listenBacklog must be >= 1, was 0");
        assertThatThrownBy(() -> new RmiTransportProperties(1, 1, null)).hasMessage("serialFilter must not be null");
        assertThatThrownBy(() -> new RmiTransportProperties.SerialFilter(0, 1, 1, 1)).hasMessage("maxDepth must be >= 1, was 0");
        assertThatThrownBy(() -> new RmiTransportProperties.SerialFilter(1, 0, 1, 1)).hasMessage("maxRefs must be >= 1, was 0");
        assertThatThrownBy(() -> new RmiTransportProperties.SerialFilter(1, 1, 0, 1)).hasMessage("maxBytes must be >= 1, was 0");
        assertThatThrownBy(() -> new RmiTransportProperties.SerialFilter(1, 1, 1, 0)).hasMessage("maxArray must be >= 1, was 0");
    }
}
