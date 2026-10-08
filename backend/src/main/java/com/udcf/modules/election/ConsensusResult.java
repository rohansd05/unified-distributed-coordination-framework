package com.udcf.modules.election;

import java.util.List;

public record ConsensusResult(
        boolean reached,
        Integer coordinatorId,
        List<Integer> disagreeingNodes
) {
}
