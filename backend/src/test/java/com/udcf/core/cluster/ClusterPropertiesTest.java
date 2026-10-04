package com.udcf.core.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Guards how capacities and ports are derived per node, and that an invalid cluster shape
 * fails startup instead of producing overlapping ports.
 */
class ClusterPropertiesTest {

    private static final List<NodeCapacity> PATTERN = List.of(FAST, MEDIUM, SLOW, MEDIUM, FAST);
    private static final ClusterProperties.Ports PORTS =
            new ClusterProperties.Ports(1100, 6000, 7000, 7100, 7200, 7300);

    /** Production registers ClusterProperties by scan; the runner needs it explicitly. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ClusterProperties.class)
    static class PropertiesRegistration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesRegistration.class)
            .withPropertyValues(
                    "udcf.cluster.size=5",
                    "udcf.cluster.capacity-pattern=FAST,MEDIUM,SLOW,MEDIUM,FAST",
                    "udcf.cluster.ports.rmi-base=1100",
                    "udcf.cluster.ports.clock-base=6000",
                    "udcf.cluster.ports.election-base=7000",
                    "udcf.cluster.ports.replication-base=7100",
                    "udcf.cluster.ports.requests-base=7200",
                    "udcf.cluster.ports.mapreduce-base=7300");

    @Test
    @DisplayName("size 3 takes the first three capacities: FAST, MEDIUM, SLOW")
    void capacityOfSizeThree() {
        ClusterProperties properties = new ClusterProperties(3, PATTERN, PORTS);

        assertThat(List.of(properties.capacityOf(1), properties.capacityOf(2), properties.capacityOf(3)))
                .containsExactly(FAST, MEDIUM, SLOW);
    }

    @Test
    @DisplayName("size 5 gives the full pattern, and a short pattern cycles")
    void capacityOfCycles() {
        ClusterProperties full = new ClusterProperties(5, PATTERN, PORTS);
        ClusterProperties cycling = new ClusterProperties(5, List.of(FAST, SLOW), PORTS);

        assertThat(List.of(1, 2, 3, 4, 5).stream().map(full::capacityOf).toList())
                .containsExactly(FAST, MEDIUM, SLOW, MEDIUM, FAST);
        assertThat(List.of(1, 2, 3, 4, 5).stream().map(cycling::capacityOf).toList())
                .containsExactly(FAST, SLOW, FAST, SLOW, FAST);
    }

    @Test
    @DisplayName("forNode gives base + id for all six services")
    void forNodeAddsId() {
        assertThat(PORTS.forNode(3)).isEqualTo(new NodePorts(1103, 6003, 7003, 7103, 7203, 7303));
        assertThat(new ClusterProperties(5, PATTERN, PORTS).portsFor(5))
                .isEqualTo(new NodePorts(1105, 6005, 7005, 7105, 7205, 7305));
    }

    @Test
    @DisplayName("capacityOf and portsFor reject node ids outside 1..size")
    void rejectsNodeIdOutsideCluster() {
        ClusterProperties properties = new ClusterProperties(5, PATTERN, PORTS);

        assertThatIllegalArgumentException().isThrownBy(() -> properties.capacityOf(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.capacityOf(6));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.portsFor(0));
        assertThatIllegalArgumentException().isThrownBy(() -> properties.portsFor(6));
    }

    @Test
    @DisplayName("forNode rejects node ids outside 1..9")
    void forNodeRejectsOutOfRange() {
        assertThatIllegalArgumentException().isThrownBy(() -> PORTS.forNode(0));
        assertThatIllegalArgumentException().isThrownBy(() -> PORTS.forNode(10));
        assertThat(PORTS.forNode(9).election()).isEqualTo(7009);
    }

    @Test
    @DisplayName("valid values bind from udcf.cluster.*")
    void validValuesBind() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ClusterProperties properties = context.getBean(ClusterProperties.class);
            assertThat(properties.size()).isEqualTo(5);
            assertThat(properties.capacityPattern()).containsExactly(FAST, MEDIUM, SLOW, MEDIUM, FAST);
            assertThat(properties.ports()).isEqualTo(PORTS);
        });
    }

    @Test
    @DisplayName("size 1 fails startup")
    void sizeOneFails() {
        runner.withPropertyValues("udcf.cluster.size=1")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("size"));
    }

    @Test
    @DisplayName("size 10 fails startup")
    void sizeTenFails() {
        runner.withPropertyValues("udcf.cluster.size=10")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("size"));
    }

    @Test
    @DisplayName("an empty capacity pattern fails startup")
    void emptyPatternFails() {
        runner.withPropertyValues("udcf.cluster.capacity-pattern=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("capacityPattern"));
    }

    @Test
    @DisplayName("port bases less than 10 apart fail startup")
    void basesTooCloseFail() {
        runner.withPropertyValues("udcf.cluster.ports.replication-base=7005")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("at least 10 apart"));
    }

    @Test
    @DisplayName("a port base of 0 fails startup")
    void baseZeroFails() {
        runner.withPropertyValues("udcf.cluster.ports.rmi-base=0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("rmiBase"));
    }

    @Test
    @DisplayName("a port base above 65526 fails startup")
    void baseTooHighFails() {
        runner.withPropertyValues("udcf.cluster.ports.mapreduce-base=65527")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("mapreduceBase"));
    }
}
