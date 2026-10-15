package com.udcf.modules.matrix.dto;

import com.udcf.core.cluster.NodeStatus;
import com.udcf.modules.rmi.dto.RmiNodeState;

import java.util.List;

/**
 * One node in the Experiment 10 overview, read live without starting anything (R10). Every cluster node is a
 * participant.
 *
 * <p>No dedicated test: a record; tested through MatrixModuleTest and MatrixControllerTest.</p>
 *
 * @param rmiState               Experiment 1's view of the node's RMI service
 * @param startFailure           why the last attempt to start RMI on this node failed; null otherwise
 * @param matrixEndpointExported whether the node's MatrixEndpoint is exported now
 * @param leader                 whether the node holds the cluster's LEADER role (Experiment 4)
 * @param keptRunIds             run ids whose computed block the node keeps, oldest first; null while its MatrixEndpoint
 *                               is not exported (unknown, not empty)
 * @param storedCollectiveIds    collective ids whose payload the node keeps; null while its CollectiveEndpoint is not
 *                               exported
 * @param requestListenerRunning whether the node's Experiment 2 request listener (and so its executor) runs now
 * @param requestsPort           the port that listener binds
 */
public record MatrixNodeDto(int nodeId, NodeStatus nodeStatus, RmiNodeState rmiState, int registryPort, String startFailure,
                            boolean matrixEndpointExported, boolean leader, List<Long> keptRunIds,
                            List<Long> storedCollectiveIds, boolean requestListenerRunning, int requestsPort) {
}
