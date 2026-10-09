package com.udcf.modules.election.dto;

import java.util.List;

/**
 * One node as the election module sees it.
 *
 * <p>No dedicated test: a plain record. ElectionModuleTest checks how it is filled.</p>
 *
 * @param status         {@code UP} or {@code CRASHED}
 * @param serviceRunning whether this node's election service is running
 * @param coordinatorId  the coordinator this node learned last; {@code null} if none or not running
 * @param suspectedPeers peers this node's failure detector suspects (empty if not running)
 * @param port           this node's election UDP port
 */
public record ElectionNodeDto(
        int nodeId,
        String status,
        boolean serviceRunning,
        Integer coordinatorId,
        List<Integer> suspectedPeers,
        int port
) {
}
