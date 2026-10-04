package com.udcf.web.dto;

import com.udcf.core.cluster.ClusterNode;

import java.util.List;

/**
 * Wire form of one cluster node.
 *
 * <p>{@code roles} (leader, primary, backup) is always empty until election arrives in
 * Phase 5.</p>
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
        return new NodeDto(node.id(), node.status().name(), List.of(), CapacityDto.from(node.capacity()),
                PortsDto.from(node.ports()), node.runningServices());
    }
}
