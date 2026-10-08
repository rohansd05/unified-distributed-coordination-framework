package com.udcf.modules.clocksync.lamport;

import java.util.Objects;

/**
 * Details of one causal ordering or monotonicity violation detected during verification.
 *
 * @param nodeId               the node where the violation occurred
 * @param type                 the specific nature of the violation
 * @param actualLamportTime    the actual Lamport timestamp found on the event
 * @param expectedRelationTime the timestamp it was expected to strictly exceed
 * @param peerId               the peer node involved, or 0 if local
 * @param message              human-readable diagnostic explanation
 */
public record CausalViolation(
        int nodeId,
        ViolationType type,
        long actualLamportTime,
        long expectedRelationTime,
        int peerId,
        String message
) {

    public enum ViolationType {
        RECEIVE_ORDER,
        LOCAL_MONOTONICITY
    }

    public CausalViolation {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
