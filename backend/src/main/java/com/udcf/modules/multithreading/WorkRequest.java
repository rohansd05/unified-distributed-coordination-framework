package com.udcf.modules.multithreading;

import com.udcf.modules.multithreading.dto.GenerateRequestsCommand;

import java.util.Objects;

/**
 * One unit of work sent to a node's requests service over TCP (see {@link RequestsProtocol}).
 *
 * <p>Validation is covered by RequestsProtocolTest.</p>
 *
 * @param senderId    node id of the sender; 0 means a cluster-level client such as a gateway
 * @param lamportTime the sender's Lamport time, ticked just before sending (link L4)
 * @param type        the workload to run
 * @param payloadSize work units, 1 to 5000 (docs/HANDOFF.md Appendix B)
 */
public record WorkRequest(int senderId, long lamportTime, WorkloadType type, int payloadSize) {

    public WorkRequest {
        if (senderId < 0) {
            throw new IllegalArgumentException("senderId must be >= 0, was " + senderId);
        }
        if (lamportTime < 0) {
            throw new IllegalArgumentException("lamportTime must be >= 0, was " + lamportTime);
        }
        Objects.requireNonNull(type, "type must not be null");
        if (payloadSize < GenerateRequestsCommand.MIN_PAYLOAD_SIZE
                || payloadSize > GenerateRequestsCommand.MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException("payloadSize must be in " + GenerateRequestsCommand.MIN_PAYLOAD_SIZE
                    + ".." + GenerateRequestsCommand.MAX_PAYLOAD_SIZE + ", was " + payloadSize);
        }
    }
}
