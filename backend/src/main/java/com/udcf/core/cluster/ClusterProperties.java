package com.udcf.core.cluster;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Shape of the shared cluster, bound from {@code udcf.cluster.*}.
 *
 * <p>No defaults in code: a missing key fails startup rather than silently using a
 * hard-coded size or port. Registered by {@code @ConfigurationPropertiesScan}.</p>
 *
 * @param size            number of nodes, 2 to 9 (node k uses port base + k, and bases are
 *                        at least 10 apart, so 9 is the most that never overlaps)
 * @param capacityPattern capacities assigned to nodes 1, 2, 3, ... cycling if shorter
 * @param ports           port bases, one per protocol endpoint
 */
@Validated
@ConfigurationProperties("udcf.cluster")
public record ClusterProperties(
        @Min(2) @Max(9) int size,
        @NotEmpty List<NodeCapacity> capacityPattern,
        @Valid @NotNull Ports ports
) {

    /** Largest node id any port base can serve without reaching the next base. */
    static final int MAX_NODE_ID = 9;

    /** Capacity of node {@code nodeId}: the pattern, cycled. */
    public NodeCapacity capacityOf(int nodeId) {
        requireInCluster(nodeId);
        return capacityPattern.get((nodeId - 1) % capacityPattern.size());
    }

    /** Ports of node {@code nodeId}, checked against the cluster size. */
    public NodePorts portsFor(int nodeId) {
        requireInCluster(nodeId);
        return ports.forNode(nodeId);
    }

    private void requireInCluster(int nodeId) {
        if (nodeId < 1 || nodeId > size) {
            throw new IllegalArgumentException("nodeId must be in 1.." + size + ", was " + nodeId);
        }
    }

    /** Port bases; node k binds base + k on each. */
    public record Ports(
            @Min(1) @Max(65526) int rmiBase,
            @Min(1) @Max(65526) int clockBase,
            @Min(1) @Max(65526) int electionBase,
            @Min(1) @Max(65526) int replicationBase,
            @Min(1) @Max(65526) int requestsBase,
            @Min(1) @Max(65526) int mapreduceBase
    ) {

        /**
         * Ports of node {@code nodeId}: base + id for each service. Rejects ids outside
         * 1..9, the most a base can serve; {@link ClusterProperties#portsFor(int)} also
         * checks the cluster size.
         */
        public NodePorts forNode(int nodeId) {
            if (nodeId < 1 || nodeId > MAX_NODE_ID) {
                throw new IllegalArgumentException(
                        "nodeId must be in 1.." + MAX_NODE_ID + ", was " + nodeId);
            }
            return new NodePorts(rmiBase + nodeId, clockBase + nodeId, electionBase + nodeId,
                    replicationBase + nodeId, requestsBase + nodeId, mapreduceBase + nodeId);
        }

        /** No two services' port ranges (base + 1 .. base + 9) may overlap. */
        @AssertTrue(message = "port bases must be at least 10 apart")
        public boolean isBasesAtLeastTenApart() {
            int[] bases = {rmiBase, clockBase, electionBase, replicationBase, requestsBase, mapreduceBase};
            for (int i = 0; i < bases.length; i++) {
                for (int j = i + 1; j < bases.length; j++) {
                    if (Math.abs(bases[i] - bases[j]) < 10) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
