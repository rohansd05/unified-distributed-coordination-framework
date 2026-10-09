package com.udcf.modules.election.dto;

import java.util.List;

/**
 * The consensus check: do all live nodes agree on one leader, and is that leader alive?
 *
 * <p>No dedicated test: a plain record. ElectionModuleTest checks how it is filled.</p>
 *
 * @param reached          every live node reports the same coordinator (E4a ConsensusChecker)
 * @param coordinatorId    that coordinator, or {@code null}
 * @param coordinatorAlive the coordinator's node is up and its election service running
 * @param passed           {@code reached && coordinatorAlive}
 * @param disagreeingNodes live nodes that report another coordinator or none (a live node whose
 *                         election service is not running reports none)
 */
public record ConsensusDto(
        boolean reached,
        Integer coordinatorId,
        boolean coordinatorAlive,
        boolean passed,
        List<Integer> disagreeingNodes
) {
}
