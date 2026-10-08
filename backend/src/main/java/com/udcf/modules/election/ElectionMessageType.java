package com.udcf.modules.election;

public enum ElectionMessageType {
    ELECTION,
    OK,
    COORDINATOR,
    RING_ELECTION,
    RING_COORDINATOR,
    PROBE,
    PROBE_ACK,
    /**
     * Failure-detector heartbeat (link L2), carried on the election socket and handed straight
     * to the node's {@code FailureDetector}; the algorithms never see it. Heartbeats are exempt
     * from Lamport (deviation from L4, see docs/tracks/track-b-swanand.md): the lamportTime
     * field is unused, always sent as 0, and must never be applied to any clock.
     */
    HEARTBEAT
}
