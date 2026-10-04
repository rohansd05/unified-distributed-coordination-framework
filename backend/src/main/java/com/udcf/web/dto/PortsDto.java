package com.udcf.web.dto;

import com.udcf.core.cluster.NodePorts;

/**
 * Wire form of a node's ports.
 *
 * <p>No dedicated test file: NodeDtoTest covers its mapping.</p>
 */
public record PortsDto(int rmi, int clock, int election, int replication, int requests, int mapreduce) {

    public static PortsDto from(NodePorts ports) {
        return new PortsDto(ports.rmi(), ports.clock(), ports.election(), ports.replication(),
                ports.requests(), ports.mapreduce());
    }
}
