package com.udcf.core.events;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One entry in the cluster-wide event log, shared by every module.
 *
 * <p>Events are ordered causally by {@code (lamportTime, nodeId, sequence)}; see
 * {@link EventRingBuffer#CAUSAL_ORDER}. {@code wallTime} is for display only and is never
 * used for ordering: every node shares one hardware clock, so it says nothing about
 * causality.</p>
 *
 * @param sequence    global arrival number assigned by the bus, starting at 1
 * @param module      module id, e.g. {@code "election"}
 * @param nodeId      originating node; 0 means a cluster-level event
 * @param type        event type, e.g. {@code "ELECTION_START"}
 * @param lamportTime the originating node's Lamport time
 * @param wallTime    display only — never used for ordering
 * @param peerId      the other node involved, if any
 * @param message     human-readable description, if any
 * @param data        extra fields; an unmodifiable, insertion-ordered copy (never null)
 */
public record ClusterEvent(
        long sequence,
        String module,
        int nodeId,
        String type,
        long lamportTime,
        Instant wallTime,
        Integer peerId,
        String message,
        Map<String, Object> data
) {

    public ClusterEvent {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be > 0, was " + sequence);
        }
        requireNonBlank(module, "module");
        requireNonBlank(type, "type");
        if (nodeId < 0) {
            throw new IllegalArgumentException("nodeId must be >= 0 (0 = cluster-level), was " + nodeId);
        }
        if (lamportTime < 0) {
            throw new IllegalArgumentException("lamportTime must be >= 0, was " + lamportTime);
        }
        Objects.requireNonNull(wallTime, "wallTime must not be null");
        data = data == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
