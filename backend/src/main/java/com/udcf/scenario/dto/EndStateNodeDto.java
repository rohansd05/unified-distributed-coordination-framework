package com.udcf.scenario.dto;

import java.util.List;

/**
 * One node as a run left it.
 *
 * <p>No dedicated test: a record; built and tested through ScenarioEngineTest.</p>
 *
 * @param status UP or CRASHED
 * @param roles  LEADER, PRIMARY, BACKUP (in that order); empty if none
 */
public record EndStateNodeDto(int nodeId, String status, List<String> roles) {
}
