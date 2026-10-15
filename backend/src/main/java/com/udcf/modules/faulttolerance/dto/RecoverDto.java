package com.udcf.modules.faulttolerance.dto;

/**
 * Answer to {@code POST /recover}: the old primary is up again and rejoins on its own (role
 * query, then demotion or resumption, then a copy of the primary's store).
 *
 * <p>No dedicated test: a record; built and tested through FaultToleranceModuleTest.</p>
 *
 * @param nodeId        the recovered old primary
 * @param previousEpoch the epoch it was primary at before its crash
 */
public record RecoverDto(int nodeId, long previousEpoch, FaultToleranceStatusDto status) {
}
