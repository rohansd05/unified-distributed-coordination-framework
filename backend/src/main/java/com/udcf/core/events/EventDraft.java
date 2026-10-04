package com.udcf.core.events;

import java.util.Map;

/**
 * What a module hands to {@link ClusterEventBus#publish(EventDraft)}.
 *
 * <p>A draft has no sequence and no wall time: the bus assigns both, so no caller can
 * forge an ordering. Validation happens once, when the bus turns the draft into a
 * {@link ClusterEvent}.</p>
 */
public record EventDraft(
        String module,
        int nodeId,
        String type,
        long lamportTime,
        Integer peerId,
        String message,
        Map<String, Object> data
) {

    /** A draft with no peer, no message and no extra data. */
    public static EventDraft of(String module, int nodeId, String type, long lamportTime) {
        return new EventDraft(module, nodeId, type, lamportTime, null, null, Map.of());
    }

    public EventDraft withPeer(Integer peerId) {
        return new EventDraft(module, nodeId, type, lamportTime, peerId, message, data);
    }

    public EventDraft withMessage(String message) {
        return new EventDraft(module, nodeId, type, lamportTime, peerId, message, data);
    }

    public EventDraft withData(Map<String, Object> data) {
        return new EventDraft(module, nodeId, type, lamportTime, peerId, message, data);
    }
}
