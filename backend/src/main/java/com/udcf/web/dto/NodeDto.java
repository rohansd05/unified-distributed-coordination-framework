package com.udcf.web.dto;

import com.udcf.core.cluster.ClusterNode;
import com.udcf.core.cluster.NodeRole;

import java.util.List;

/**
 * Wire form of one cluster node.
 *
 * <p>{@code roles} lists the {@link NodeRole} names the node holds, for example
 * {@code ["LEADER"]} on the elected leader (Experiment 4); empty until an election has run, and
 * always empty on a crashed node.</p>
 */
public record NodeDto(
        int id,
        String status,
        List<String> roles,
        CapacityDto capacity,
        PortsDto ports,
        List<String> runningServices
) {

    public static NodeDto from(ClusterNode node) {
        return new NodeDto(node.id(), node.status().name(), node.roles().stream().map(NodeRole::name).toList(),
                CapacityDto.from(node.capacity()), PortsDto.from(node.ports()), node.runningServices());
    }
}
