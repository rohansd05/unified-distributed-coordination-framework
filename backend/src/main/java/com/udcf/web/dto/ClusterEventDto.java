package com.udcf.web.dto;

import com.udcf.core.events.ClusterEvent;

import java.util.Map;

/**
 * Wire form of a {@link ClusterEvent} for STOMP and REST.
 *
 * <p>{@code wallTime} is an ISO-8601 string (e.g. {@code 2026-01-01T00:00:00Z}), so the
 * format does not depend on any ObjectMapper setting. It is for display only; clients
 * order events by {@code (lamportTime, nodeId, sequence)}.</p>
 */
public record ClusterEventDto(
        long sequence,
        String module,
        int nodeId,
        String type,
        long lamportTime,
        String wallTime,
        Integer peerId,
        String message,
        Map<String, Object> data
) {

    public static ClusterEventDto from(ClusterEvent event) {
        return new ClusterEventDto(event.sequence(), event.module(), event.nodeId(), event.type(),
                event.lamportTime(), event.wallTime().toString(), event.peerId(), event.message(),
                event.data());
    }
}
