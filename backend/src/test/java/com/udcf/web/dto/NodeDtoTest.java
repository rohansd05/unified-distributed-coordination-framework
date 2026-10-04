package com.udcf.web.dto;

import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.cluster.NodePorts;
import com.udcf.core.cluster.NodeService;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the node wire form, including its capacity and ports parts. */
class NodeDtoTest {

    private static final NodePorts PORTS = new NodePorts(1102, 6002, 7002, 7102, 7202, 7302);

    /** Minimal running service; binds nothing. */
    private static final class StubService implements NodeService {
        private final String name;
        private boolean running;

        StubService(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void crash() {
            running = false;
        }

        @Override
        public void recover() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    private ClusterEventBus bus;

    @BeforeEach
    void setUp() {
        bus = new ClusterEventBus(new EventProperties(100, 100), Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        bus.close();
    }

    @Test
    @DisplayName("CapacityDto carries the profile name, threads and multiplier")
    void capacityMapping() {
        assertThat(CapacityDto.from(NodeCapacity.SLOW)).isEqualTo(new CapacityDto("SLOW", 1, 4));
    }

    @Test
    @DisplayName("PortsDto mirrors every port")
    void portsMapping() {
        assertThat(PortsDto.from(PORTS)).isEqualTo(new PortsDto(1102, 6002, 7002, 7102, 7202, 7302));
    }

    @Test
    @DisplayName("NodeDto maps id, status, capacity, ports and running services, with no roles yet")
    void nodeMapping() {
        ClusterNode node = new ClusterNode(2, NodeCapacity.MEDIUM, PORTS, bus);
        node.ensureService("election", n -> new StubService("election"));
        node.ensureService("replication", n -> new StubService("replication"));

        NodeDto up = NodeDto.from(node);
        node.crash();
        NodeDto crashed = NodeDto.from(node);

        assertThat(up).isEqualTo(new NodeDto(2, "UP", List.of(), new CapacityDto("MEDIUM", 2, 2),
                PortsDto.from(PORTS), List.of("election", "replication")));
        assertThat(crashed.status()).isEqualTo("CRASHED");
        assertThat(crashed.runningServices()).isEmpty();
        assertThat(crashed.roles()).isEmpty();
    }
}
