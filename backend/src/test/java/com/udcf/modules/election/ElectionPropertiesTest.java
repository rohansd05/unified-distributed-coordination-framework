package com.udcf.modules.election;

import com.udcf.core.failure.FailureDetectorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Guards the Experiment 4 settings as bound from the real application.yml (Appendix B), and
 * that invalid values fail startup. No sockets.
 */
class ElectionPropertiesTest {

    private static final ElectionProperties APPENDIX_B = new ElectionProperties(900, 2200, 300, 5000, 700, 2500, 10000);

    /** Production registers ElectionProperties by scan; the runner needs it explicitly. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ElectionProperties.class)
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    @DisplayName("application.yml binds the Appendix B timings and a 5000 ms ring completion timeout, in both profiles")
    void bindsYamlDefaultsFromAppendixB() {
        runner.run(context -> assertThat(context.getBean(ElectionProperties.class)).isEqualTo(APPENDIX_B));
        runner.withPropertyValues("spring.profiles.active=public")
                .run(context -> assertThat(context.getBean(ElectionProperties.class)).isEqualTo(APPENDIX_B));
    }

    @Test
    @DisplayName("converts to the E4a ElectionConfig and the failure detector config")
    void convertsToElectionAndDetectorConfigs() {
        assertThat(APPENDIX_B.toElectionConfig()).isEqualTo(new ElectionConfig(900, 2200, 300, 5000));
        assertThat(APPENDIX_B.toFailureDetectorConfig()).isEqualTo(new FailureDetectorConfig(700, 2500));
    }

    @Test
    @DisplayName("zero values, a probe timeout not below the OK timeout, or a heartbeat timeout not above the interval fail")
    void rejectsInvalidValues() {
        runner.withPropertyValues("udcf.election.ok-timeout-millis=0")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("okTimeoutMillis"));
        runner.withPropertyValues("udcf.election.probe-timeout-millis=900")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("probeTimeoutMillis"));
        runner.withPropertyValues("udcf.election.heartbeat-timeout-millis=700")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("timeoutMillis"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ElectionProperties(900, 2200, 300, 0, 700, 2500, 10000));
        assertThatIllegalArgumentException().isThrownBy(() -> new ElectionProperties(900, 2200, 300, 5000, 0, 2500, 10000));
    }

    @Test
    @DisplayName("the round timeout must exceed the ring completion timeout and OK + coordinator timeouts")
    void rejectsRoundTimeoutNotAboveElectionTimeouts() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ElectionProperties(900, 2200, 300, 5000, 700, 2500, 5000));
        assertThatIllegalArgumentException().isThrownBy(() -> new ElectionProperties(900, 4200, 300, 3000, 700, 2500, 5100));
        assertThat(new ElectionProperties(900, 2200, 300, 5000, 700, 2500, 5001).roundTimeoutMillis()).isEqualTo(5001);
        runner.withPropertyValues("udcf.election.round-timeout-millis=5000")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("roundTimeoutMillis"));
    }
}
