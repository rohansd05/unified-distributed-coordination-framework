package com.udcf.modules.faulttolerance.dto;

/**
 * Answer to {@code POST /crash-primary}: the primary crashed on every protocol; the failover
 * follows on its own.
 *
 * <p>No dedicated test: a record; built and tested through FaultToleranceModuleTest.</p>
 *
 * @param nodeId      the crashed primary
 * @param epoch       the epoch it was primary at
 * @param crashSource always ACTION: the crash instant was stamped by this experiment
 */
public record CrashDto(int nodeId, long epoch, String crashSource, FaultToleranceStatusDto status) {
}
