package com.udcf.modules.mpi.dto;

import com.udcf.core.cluster.NodeStatus;
import com.udcf.modules.rmi.dto.RmiNodeState;

import java.util.List;

/**
 * One node in the Experiment 9 overview, read live (R10). Every cluster node is a participant.
 *
 * <p>No dedicated test: a record; tested through MpiModuleTest and MpiControllerTest.</p>
 *
 * @param nodeStatus                 UP or CRASHED (the cluster's view)
 * @param rmiState                   NOT_STARTED, EXPORTED or CRASHED (Experiment 1's view of the node's RMI service)
 * @param registryPort               the node's RMI registry port
 * @param startFailure               why the last attempt to start RMI on this node failed; null otherwise
 * @param collectiveEndpointExported whether the node's CollectiveEndpoint is exported now
 * @param leader                     whether the node holds the cluster's LEADER role (Experiment 4)
 * @param storedCollectiveIds        the collective ids whose payload the node keeps, oldest first; null while its
 *                                   CollectiveEndpoint is not exported (unknown, not empty)
 */
public record MpiNodeDto(int nodeId, NodeStatus nodeStatus, RmiNodeState rmiState, int registryPort, String startFailure,
                         boolean collectiveEndpointExported, boolean leader, List<Long> storedCollectiveIds) {
}
