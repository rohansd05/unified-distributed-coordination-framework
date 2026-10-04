package com.udcf.web.dto;

import com.udcf.core.cluster.NodeCapacity;

/**
 * Wire form of a node's capacity profile.
 *
 * <p>No dedicated test file: NodeDtoTest covers its mapping.</p>
 */
public record CapacityDto(String name, int threads, int workMultiplier) {

    public static CapacityDto from(NodeCapacity capacity) {
        return new CapacityDto(capacity.name(), capacity.threads(), capacity.workMultiplier());
    }
}
