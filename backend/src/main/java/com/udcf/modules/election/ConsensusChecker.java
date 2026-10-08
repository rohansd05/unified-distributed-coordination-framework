package com.udcf.modules.election;

import java.util.ArrayList;
import java.util.List;

public class ConsensusChecker {

    public static ConsensusResult check(List<? extends ElectionParticipant> nodes) {
        Integer agreedCoordinator = null;
        boolean foundAlive = false;

        // Find the first non-null coordinator among alive nodes to act as the baseline
        for (ElectionParticipant node : nodes) {
            if (node.isCrashed()) continue;
            foundAlive = true;
            if (node.getCoordinatorId() != null) {
                agreedCoordinator = node.getCoordinatorId();
                break;
            }
        }

        if (!foundAlive) {
            // All nodes are crashed
            return new ConsensusResult(true, null, List.of());
        }

        List<Integer> disagreeingNodes = new ArrayList<>();
        for (ElectionParticipant node : nodes) {
            if (node.isCrashed()) continue;

            if (agreedCoordinator == null) {
                disagreeingNodes.add(node.getNodeId());
            } else if (!agreedCoordinator.equals(node.getCoordinatorId())) {
                disagreeingNodes.add(node.getNodeId());
            }
        }

        if (agreedCoordinator == null) {
            return new ConsensusResult(false, null, disagreeingNodes);
        }

        return new ConsensusResult(disagreeingNodes.isEmpty(), disagreeingNodes.isEmpty() ? agreedCoordinator : null, disagreeingNodes);
    }
}
