package com.udcf.modules.multithreading;

import java.util.Objects;

/**
 * A node's answer to a {@link WorkRequest} (see {@link RequestsProtocol}).
 *
 * <p>Validation is covered by RequestsProtocolTest.</p>
 *
 * @param nodeId           the node that ran (or refused) the work
 * @param lamportTime      that node's Lamport time, ticked just before replying (link L4)
 * @param requestId        the id the node gave the request
 * @param status           COMPLETED, FAILED or REJECTED
 * @param threadName       the worker thread that ran it; null when REJECTED or never started
 * @param queueWaitMillis  time spent in the node's queue
 * @param processingMillis time spent on the worker thread
 * @param totalMillis      queue wait plus processing, as the node measured it
 * @param detail           the work summary, or the reason it failed or was refused; never null
 */
public record WorkReply(
        int nodeId,
        long lamportTime,
        String requestId,
        RequestStatus status,
        String threadName,
        double queueWaitMillis,
        double processingMillis,
        double totalMillis,
        String detail
) {

    public WorkReply {
        if (nodeId < 1) {
            throw new IllegalArgumentException("nodeId must be >= 1, was " + nodeId);
        }
        if (lamportTime < 0) {
            throw new IllegalArgumentException("lamportTime must be >= 0, was " + lamportTime);
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        Objects.requireNonNull(status, "status must not be null");
        if (status == RequestStatus.QUEUED || status == RequestStatus.PROCESSING) {
            throw new IllegalArgumentException("a reply carries a final status, was " + status);
        }
        if (threadName != null && threadName.isEmpty()) {
            threadName = null;
        }
        detail = detail == null ? "" : detail;
    }
}
