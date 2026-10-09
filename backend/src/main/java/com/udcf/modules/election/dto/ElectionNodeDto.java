package com.udcf.modules.election.dto;

import java.util.List;

/**
 * One node as the election module sees it.
 *
 * <p>No dedicated test: a plain record. ElectionModuleTest checks how it is filled.</p>
 *
 * @param status             {@code UP} or {@code CRASHED}
 * @param serviceRunning     whether this node's election service is running
 * @param coordinatorId      the coordinator this node learned last; {@code null} if none or not running
 * @param suspectedPeers     peers this node's failure detector suspects (empty if not running)
 * @param port               this node's election UDP port
 * @param electionsWon       rounds this node won since the backend started, read from
 *                           {@code distributed_leader_elections_total{node_id}} (node_id = the
 *                           elected leader); 0 is a real count
 * @param roundsTimed        rounds started from this node that ended ELECTED since the backend
 *                           started, read from the count of {@code distributed_election_duration{node_id}}
 *                           (node_id = the node the round started from; timed-out rounds are not
 *                           recorded)
 * @param meanDurationMillis mean of those rounds' measured durations; {@code null} when
 *                           {@code roundsTimed} is 0 (unmeasured, never 0)
 */
public record ElectionNodeDto(
        int nodeId,
        String status,
        boolean serviceRunning,
        Integer coordinatorId,
        List<Integer> suspectedPeers,
        int port,
        long electionsWon,
        long roundsTimed,
        Double meanDurationMillis
) {
}
