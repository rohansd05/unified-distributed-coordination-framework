package com.udcf.modules.faulttolerance.dto;

/**
 * One instant of a failover run, for the page's timeline.
 *
 * <p>No dedicated test: a record; mapped and tested in FaultToleranceDtoMapperTest.</p>
 *
 * @param kind         CRASHED, DETECTED, ELECTED, PROMOTED, SERVICE_RESTORED,
 *                     OLD_PRIMARY_RECOVERED, DEMOTED or RESYNCHRONISED
 * @param nodeId       the node it happened on; null if no longer known (a chosen node that failed)
 * @param offsetMillis milliseconds after the run's first instant, on the backend's monotonic clock
 */
public record MilestoneDto(String kind, Integer nodeId, double offsetMillis) {
}
