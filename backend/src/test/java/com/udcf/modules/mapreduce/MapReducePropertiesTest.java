package com.udcf.modules.mapreduce;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests binding of MapReduceProperties from application.yml and validation constraints.
 */
class MapReducePropertiesTest {

    private static final MapReduceProperties LOCAL_DEFAULTS =
            new MapReduceProperties(15000, 15000, 4194304, 50, 4, 32);

    private static final MapReduceProperties PUBLIC_DEFAULTS =
            new MapReduceProperties(15000, 15000, 1048576, 50, 4, 32);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MapReduceProperties.class)
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    @DisplayName("application.yml binds default MapReduce properties in local profile")
    void bindsLocalYamlDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            MapReduceProperties props = context.getBean(MapReduceProperties.class);
            assertThat(props).isEqualTo(LOCAL_DEFAULTS);
        });
    }

    @Test
    @DisplayName("application-public.yml overrides max-request-bytes to 1 MB in public profile")
    void bindsPublicYamlDefaults() {
        runner.withPropertyValues("spring.profiles.active=public")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MapReduceProperties props = context.getBean(MapReduceProperties.class);
                    assertThat(props).isEqualTo(PUBLIC_DEFAULTS);
                });
    }

    @Test
    @DisplayName("rejects zero or negative property values")
    void rejectsZeroOrNegativeValues() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new MapReduceProperties(0, 15000, 4194304, 50, 4, 32));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new MapReduceProperties(15000, 0, 4194304, 50, 4, 32));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new MapReduceProperties(15000, 15000, 0, 50, 4, 32));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new MapReduceProperties(15000, 15000, 4194304, 0, 4, 32));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new MapReduceProperties(15000, 15000, 4194304, 50, 0, 32));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new MapReduceProperties(15000, 15000, 4194304, 50, 4, 0));

        runner.withPropertyValues("udcf.mapreduce.task-timeout-millis=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
