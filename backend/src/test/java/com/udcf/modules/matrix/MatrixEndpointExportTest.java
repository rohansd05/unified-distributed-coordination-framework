package com.udcf.modules.matrix;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.modules.multithreading.RequestsNodeService;
import com.udcf.modules.rmi.CallContext;
import com.udcf.modules.rmi.RmiProperties;
import com.udcf.modules.rmi.RmiSerialFilter;
import com.udcf.modules.rmi.RmiTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The matrix export without sockets: its name, type and filter, the startup check of the two timeouts, and a
 * fresh logic per start wired to the node's own clock and collective endpoint. The cluster's bases are never
 * bound (Track C, E10b: 28600-28650; no service starts here).
 */
class MatrixEndpointExportTest {

    private final ClusterEventBus bus = RmiTestSupport.bus();
    private final Cluster cluster = new Cluster(new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
            new ClusterProperties.Ports(28650, 28600, 28610, 28620, 28640, 28630)), bus);

    @AfterEach
    void tearDown() {
        cluster.close();
        bus.close();
    }

    private static MatrixEndpointExport export(MatrixRunProperties run, RmiProperties rmi) {
        return new MatrixEndpointExport(run, rmi, RmiTestSupport.TRANSPORT, node -> InMemoryMatrixNodes.DIRECT);
    }

    @Test
    @DisplayName("bound as MatrixEndpoint, typed MatrixEndpoint, filtered by E1b's filter (CallContext only, every array rejected)")
    void nameTypeFilter() {
        MatrixEndpointExport export = export(MatrixRmiFixture.RUN, RmiTestSupport.PROPERTIES);
        assertThat(export.boundName()).isEqualTo("MatrixEndpoint");
        assertThat(export.remoteType()).isEqualTo(MatrixEndpoint.class);
        assertThat(((RmiSerialFilter) export.filter()).pattern())
                .isEqualTo(RmiSerialFilter.from(RmiTestSupport.TRANSPORT.serialFilter()).pattern());
    }

    @Test
    @DisplayName("startup fails unless compute-timeout-millis is below call-timeout-millis, naming both settings")
    void computeBelowCall() {
        RmiProperties call3000 = RmiTestSupport.PROPERTIES;
        assertThatThrownBy(() -> export(new MatrixRunProperties(1, 3000, 4), call3000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("udcf.matrix.compute-timeout-millis (3000) must be below udcf.rmi.call-timeout-millis (3000), "
                        + "so a node answers before the caller's read timeout");
        assertThat(export(new MatrixRunProperties(1, 2999, 4), call3000).boundName()).isEqualTo("MatrixEndpoint");
    }

    @Test
    @DisplayName("every start gets a fresh logic on the node's own clock; without the node's RMI running it holds no input")
    void freshLogicPerStart() throws Exception {
        MatrixEndpointExport export = export(MatrixRmiFixture.RUN, RmiTestSupport.PROPERTIES);
        ClusterNode node = cluster.node(2);
        MatrixEndpointLogic first = (MatrixEndpointLogic) export.newEndpoint(node);
        MatrixEndpointLogic second = (MatrixEndpointLogic) export.newEndpoint(node);
        assertThat(first).isNotSameAs(second);
        assertThat(first.nodeId()).isEqualTo(2);

        long before = node.clock().current();
        MatrixComputeException missing = catchThrowableOfType(() -> first.computeBlock(new CallContext(1, 0), 1, 0, 4, 11, 12),
                MatrixComputeException.class);
        assertThat(missing.reason()).isEqualTo(MatrixComputeException.Reason.INPUT_MISSING);
        assertThat(missing.getMessage()).isEqualTo("Node 2 has no collective endpoint running, so it holds no scattered rows.");
        assertThat(node.clock().current()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("the Spring constructor wires each node to its Experiment 2 executor without starting anything")
    void springConstructorStartsNothing() {
        MatrixEndpointExport export = new MatrixEndpointExport(MatrixRmiFixture.RUN, RmiTestSupport.PROPERTIES, RmiTestSupport.TRANSPORT,
                MatrixRmiFixture.MULTITHREADING, new SimpleMeterRegistry(), bus);
        assertThat(export.newEndpoint(cluster.node(1))).isInstanceOf(MatrixEndpointLogic.class);
        assertThat(RequestsNodeService.find(cluster.node(1))).isEmpty();
    }
}
