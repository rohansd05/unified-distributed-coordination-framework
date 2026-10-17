package com.udcf.modules.faulttolerance;

import com.udcf.modules.election.ElectionProperties;
import com.udcf.modules.replication.ReplicationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The Experiment 8 settings as bound from the real application.yml, their validation, and the
 * worst-case failover formula the client retry window must cover. No sockets.
 */
class FaultTolerancePropertiesTest {

    private static final FaultToleranceProperties YAML = new FaultToleranceProperties(
            new FaultToleranceProperties.Client(50, 250), new FaultToleranceProperties.RoleQuerySettings(1000, 5),
            new FaultToleranceProperties.Promotion(1000), 10);

    /** Production registers the properties by scan; the runner needs them explicitly. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({FaultToleranceProperties.class, ElectionProperties.class,
            ReplicationProperties.class})
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesRegistration.class);

    @Test
    @DisplayName("application.yml binds the udcf.faulttolerance block, in both profiles")
    void bindsYaml() {
        runner.run(context -> assertThat(context.getBean(FaultToleranceProperties.class)).isEqualTo(YAML));
        runner.withPropertyValues("spring.profiles.active=public")
                .run(context -> assertThat(context.getBean(FaultToleranceProperties.class)).isEqualTo(YAML));
    }

    @Test
    @DisplayName("zero or missing values fail startup")
    void rejectsInvalidValues() {
        runner.withPropertyValues("udcf.faulttolerance.client.max-attempts=0")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("maxAttempts"));
        runner.withPropertyValues("udcf.faulttolerance.promotion.catch-up-timeout-millis=0")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("catchUpTimeoutMillis"));
        assertThatIllegalArgumentException().isThrownBy(() -> new FaultToleranceProperties.Client(1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new FaultToleranceProperties.RoleQuerySettings(1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new FaultToleranceProperties(YAML.client(),
                YAML.roleQuery(), YAML.promotion(), 0));
    }

    @Test
    @DisplayName("worst case = (heartbeat timeout + interval) + (ok + coordinator timeout) + (catch-up + 2 x replication timeout)")
    void worstCaseFormula() {
        assertThat(FaultToleranceProperties.promotionAttemptBoundMillis(1000, 1500)).isEqualTo(4000);
        assertThat(FaultToleranceProperties.worstCaseFailoverMillis(700, 2500, 900, 2200, 1000, 1500)).isEqualTo(10300);
        assertThat(FaultToleranceProperties.worstCaseFailoverMillis(100, 600, 400, 1500, 250, 300)).isEqualTo(3450);
        assertThat(YAML.retryPolicy()).isEqualTo(new UpdateRetryPolicy(50, 250));
        assertThat(YAML.retryWindowMillis()).isEqualTo(12500);
    }

    @Test
    @DisplayName("in both profiles the configured retry window exceeds the configured worst-case failover (10300 ms) by at least 10 %")
    void yamlWindowCoversWorstCaseWithMargin() {
        for (ApplicationContextRunner profile : java.util.List.of(runner,
                runner.withPropertyValues("spring.profiles.active=public"))) {
            profile.run(context -> {
                FaultToleranceProperties properties = context.getBean(FaultToleranceProperties.class);
                ElectionProperties election = context.getBean(ElectionProperties.class);
                ReplicationProperties replication = context.getBean(ReplicationProperties.class);
                long worst = properties.worstCaseFailoverMillis(election, replication);
                assertThat(worst).isEqualTo(10300);
                assertThat(properties.retryWindowMillis()).isGreaterThanOrEqualTo(Math.round(worst * 1.10));
                assertThatCode(() -> properties.requireWindowCovers(election, replication)).doesNotThrowAnyException();
            });
        }
    }

    @Test
    @DisplayName("a retry window below the worst-case failover is rejected, naming both numbers")
    void tooSmallWindowIsRejected() {
        ElectionProperties election = new ElectionProperties(900, 2200, 300, 5000, 700, 2500, 10000);
        ReplicationProperties replication = new ReplicationProperties(450, 1500, 200);
        FaultToleranceProperties small = new FaultToleranceProperties(new FaultToleranceProperties.Client(40, 250),
                YAML.roleQuery(), YAML.promotion(), 10);
        assertThatIllegalStateException().isThrownBy(() -> small.requireWindowCovers(election, replication))
                .withMessageContaining("10000 ms").withMessageContaining("10300 ms")
                .withMessageContaining("election (900 + 2200)");
        FaultToleranceProperties exact = new FaultToleranceProperties(new FaultToleranceProperties.Client(103, 100),
                YAML.roleQuery(), YAML.promotion(), 10);
        assertThatCode(() -> exact.requireWindowCovers(election, replication)).doesNotThrowAnyException();
    }
}
