package com.udcf.modules.rmi.dto;

import com.udcf.core.cluster.NodeStatus;

/**
 * One node in the Experiment 1 overview, read live (R10).
 *
 * <p>No dedicated test: a record; tested through RmiModuleTest and RmiControllerTest.</p>
 *
 * @param nodeStatus   UP or CRASHED (the cluster's view)
 * @param state        NOT_STARTED, EXPORTED or CRASHED
 * @param registryPort the node's RMI registry port ({@code ports().rmi()})
 * @param startFailure why the module's last attempt to start RMI on this node failed; null otherwise
 */
public record RmiNodeDto(int nodeId, NodeStatus nodeStatus, RmiNodeState state, int registryPort, String startFailure) {
}
