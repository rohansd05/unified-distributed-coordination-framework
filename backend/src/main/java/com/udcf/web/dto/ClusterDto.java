package com.udcf.web.dto;

import com.udcf.core.cluster.Cluster;

import java.util.List;

/** Wire form of the whole cluster for {@code GET /api/cluster}. */
public record ClusterDto(int size, long upCount, List<NodeDto> nodes) {

    public static ClusterDto from(Cluster cluster) {
        return new ClusterDto(cluster.size(), cluster.upCount(),
                cluster.nodes().stream().map(NodeDto::from).toList());
    }
}
