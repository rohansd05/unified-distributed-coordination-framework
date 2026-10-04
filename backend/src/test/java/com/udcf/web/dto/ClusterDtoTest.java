package com.udcf.web.dto;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterProperties;
import com.udcf.core.events.ClusterEventBus;
import com.udcf.core.events.EventProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static com.udcf.core.cluster.NodeCapacity.FAST;
import static com.udcf.core.cluster.NodeCapacity.MEDIUM;
import static com.udcf.core.cluster.NodeCapacity.SLOW;
import static org.assertj.core.api.Assertions.assertThat;

/** Guards the cluster wire form: size, up count and every node in id order. */
class ClusterDtoTest {

    @Test
    @DisplayName("from maps size, upCount and every node in id order")
    void clusterMapping() {
        ClusterEventBus bus = new ClusterEventBus(new EventProperties(100, 100), Clock.systemUTC());
        Cluster cluster = new Cluster(new ClusterProperties(3, List.of(FAST, MEDIUM, SLOW),
                new ClusterProperties.Ports(1100, 6000, 7000, 7100, 7200, 7300)), bus);
        try {
            cluster.crash(2);

            ClusterDto dto = ClusterDto.from(cluster);

            assertThat(dto.size()).isEqualTo(3);
            assertThat(dto.upCount()).isEqualTo(2);
            assertThat(dto.nodes()).extracting(NodeDto::id).containsExactly(1, 2, 3);
            assertThat(dto.nodes()).extracting(NodeDto::status).containsExactly("UP", "CRASHED", "UP");
            assertThat(dto.nodes().get(2)).isEqualTo(NodeDto.from(cluster.node(3)));
        } finally {
            cluster.close();
            bus.close();
        }
    }
}
