package com.udcf.modules.mapreduce;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Binding of MapReduceModuleProperties from both profiles, its validation, and the startup
 * sizing rule (upload cap at most a quarter of max-request-bytes) in every profile.
 */
class MapReduceModulePropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({MapReduceModuleProperties.class, MapReduceProperties.class})
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesRegistration.class);

    private static MapReduceProperties wire(int maxRequestBytes) {
        return new MapReduceProperties(15000, 15000, maxRequestBytes, 50, 4, 32);
    }

    @Test
    @DisplayName("local profile: upload cap 1 MiB, a quarter of max-request-bytes 4 MiB, so the startup check passes")
    void localProfile() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            MapReduceModuleProperties props = context.getBean(MapReduceModuleProperties.class);
            MapReduceProperties wire = context.getBean(MapReduceProperties.class);
            assertThat(props).isEqualTo(new MapReduceModuleProperties(1048576, 200, 20, 8192));
            assertThat((long) props.uploadMaxBytes() * 4).isEqualTo(wire.maxRequestBytes());
            props.requireFitsWire(wire);
        });
    }

    @Test
    @DisplayName("public profile: upload cap 256 KiB, a quarter of max-request-bytes 1 MiB, so the startup check passes")
    void publicProfile() {
        runner.withPropertyValues("spring.profiles.active=public").run(context -> {
            assertThat(context).hasNotFailed();
            MapReduceModuleProperties props = context.getBean(MapReduceModuleProperties.class);
            MapReduceProperties wire = context.getBean(MapReduceProperties.class);
            assertThat(props).isEqualTo(new MapReduceModuleProperties(262144, 200, 20, 8192));
            assertThat(wire.maxRequestBytes()).isEqualTo(1048576);
            props.requireFitsWire(wire);
        });
    }

    @Test
    @DisplayName("the startup check accepts exactly a quarter and fails fast with a clear message one byte above it")
    void sizingRule() {
        new MapReduceModuleProperties(262144, 200, 20, 8192).requireFitsWire(wire(1048576));

        assertThatIllegalStateException()
                .isThrownBy(() -> new MapReduceModuleProperties(262145, 200, 20, 8192).requireFitsWire(wire(1048576)))
                .withMessageContaining("upload-max-bytes (262145)")
                .withMessageContaining("max-request-bytes (1048576)")
                .withMessageContaining("at most a quarter");
    }

    @Test
    @DisplayName("Base64 size of the cap and the request body limit")
    void derivedSizes() {
        assertThat(new MapReduceModuleProperties(3, 1, 1, 0).uploadBase64Chars()).isEqualTo(4);
        assertThat(new MapReduceModuleProperties(4, 1, 1, 0).uploadBase64Chars()).isEqualTo(8);
        MapReduceModuleProperties publicProps = new MapReduceModuleProperties(262144, 200, 20, 8192);
        assertThat(publicProps.uploadBase64Chars()).isEqualTo(349528);
        assertThat(publicProps.requestBodyMaxBytes()).isEqualTo(349528 + 8192);
    }

    @Test
    @DisplayName("rejects out-of-range values, in code and when binding")
    void validation() {
        assertThatIllegalArgumentException().isThrownBy(() -> new MapReduceModuleProperties(0, 200, 20, 8192));
        assertThatIllegalArgumentException().isThrownBy(() -> new MapReduceModuleProperties(1, 0, 20, 8192));
        assertThatIllegalArgumentException().isThrownBy(() -> new MapReduceModuleProperties(1, 200, 0, 8192));
        assertThatIllegalArgumentException().isThrownBy(() -> new MapReduceModuleProperties(1, 200, 20, -1));

        runner.withPropertyValues("udcf.mapreduce.run-history-size=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
