package com.udcf.modules.clocksync.dto;

/**
 * Details of a causal violation in Lamport ordering or local monotonicity.
 */
public record CausalViolationDto(
        int nodeId,
        String type,
        long actualLamportTime,
        long expectedRelationTime,
        int peerId,
        String message
) {
}
