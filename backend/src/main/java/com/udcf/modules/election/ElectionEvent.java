package com.udcf.modules.election;

import java.time.Instant;

public record ElectionEvent(
        int nodeId,
        ElectionEventType type,
        long lamportTime,
        int peerId,
        String description,
        Instant timestamp
) {
}
