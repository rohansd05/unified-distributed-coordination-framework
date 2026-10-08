package com.udcf.modules.multithreading.dto;

import com.udcf.core.cluster.NodeCapacity;
import com.udcf.core.cluster.NodeStatus;

/**
 * One node as Experiment 2 sees it.
 *
 * <p>No dedicated test: a data carrier. MultithreadingControllerTest asserts its JSON fields.</p>
 *
 * @param nodeId             the node
 * @param nodeStatus         UP or CRASHED
 * @param capacity           FAST, MEDIUM or SLOW
 * @param capacityConfigured always true: the capacity profile is set in configuration, not
 *                           measured, because every node shares one machine (see the
 *                           overview's capacityNote)
 * @param threads            worker threads this node's executor has
 * @param workMultiplier     how many times the work each request costs on this node
 * @param port               the node's requests TCP port
 * @param serviceRunning     whether the node's requests service is running (it starts on first use)
 * @param stats              live executor snapshot; null while the service is not running
 */
public record NodeRequestsDto(
        int nodeId,
        NodeStatus nodeStatus,
        NodeCapacity capacity,
        boolean capacityConfigured,
        int threads,
        int workMultiplier,
        int port,
        boolean serviceRunning,
        ThreadPoolStats stats
) {
}
