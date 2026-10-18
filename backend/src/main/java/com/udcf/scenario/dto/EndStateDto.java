package com.udcf.scenario.dto;

import java.util.List;

/**
 * The cluster state a run leaves behind (read from the cluster when the run ends; there is no
 * automatic reset, so the audience can inspect it).
 *
 * <p>No dedicated test: a record; built and tested through ScenarioEngineTest.</p>
 *
 * @param description sentences, for example "Nodes 1, 2, 3 and 4 are up; node 5 is down. Node 4 is the
 *                    leader and the replication primary. The update stream is stopped."
 * @param leaderId    null if no node is the leader
 * @param primaryId   null if no node is the replication primary
 */
public record EndStateDto(String description, Integer leaderId, Integer primaryId, List<EndStateNodeDto> nodes) {
}
