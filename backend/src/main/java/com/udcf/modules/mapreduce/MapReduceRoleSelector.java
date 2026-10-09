package com.udcf.modules.mapreduce;

import com.udcf.core.cluster.Cluster;
import com.udcf.core.cluster.ClusterNode;

import java.util.List;
import java.util.Objects;

/**
 * Module-local role selection for MapReduce coordinator and worker nodes.
 *
 * <p>Coordinator is chosen as the lowest live node ID. Workers are all live nodes
 * including the coordinator.</p>
 */
public final class MapReduceRoleSelector {

    private MapReduceRoleSelector() {
    }

    /**
     * Selects the coordinator node ID as the lowest-numbered live node in the cluster.
     *
     * // TODO(L1): replaced by the shared role provider in Phase 9A.
     *
     * @param cluster the cluster
     * @return the coordinator node ID
     * @throws IllegalStateException if no nodes are live
     */
    public static int selectCoordinator(Cluster cluster) {
        Objects.requireNonNull(cluster, "cluster must not be null");
        return cluster.nodes().stream()
                .filter(ClusterNode::isUp)
                .mapToInt(ClusterNode::id)
                .min()
                .orElseThrow(() -> new IllegalStateException("No live nodes available in cluster for coordinator"));
    }

    /**
     * Selects the worker node IDs as all live nodes in the cluster, including the coordinator.
     *
     * // TODO(L1): replaced by the shared role provider in Phase 9A.
     *
     * @param cluster the cluster
     * @return sorted list of live worker node IDs
     */
    public static List<Integer> selectWorkers(Cluster cluster) {
        Objects.requireNonNull(cluster, "cluster must not be null");
        return cluster.nodes().stream()
                .filter(ClusterNode::isUp)
                .map(ClusterNode::id)
                .sorted()
                .toList();
    }
}
