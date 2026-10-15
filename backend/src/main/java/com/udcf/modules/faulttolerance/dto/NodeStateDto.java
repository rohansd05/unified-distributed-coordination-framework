package com.udcf.modules.faulttolerance.dto;

/**
 * One node as Experiment 8 sees it now. Every value is read from the node's own services.
 *
 * <p>No dedicated test: a record; mapped and tested in FaultToleranceDtoMapperTest.</p>
 *
 * @param nodeStatus        UP or CRASHED
 * @param role              PRIMARY or BACKUP, from its replication service; null if the node is
 *                          down or has no replication service
 * @param serving           whether client updates go to it
 * @param epoch             the epoch it acts at as primary, else its store epoch; null if the node
 *                          is down or has no replication service
 * @param believedPrimaryId the primary named by its own term record; null if none or the node is down
 * @param rejoin            READY, REJOINING or WAITING_FOR_ANSWER; null if the node is down or has no
 *                          faulttolerance service
 */
public record NodeStateDto(int nodeId, String nodeStatus, String role, boolean serving, Long epoch,
                           Integer believedPrimaryId, String rejoin) {
}
