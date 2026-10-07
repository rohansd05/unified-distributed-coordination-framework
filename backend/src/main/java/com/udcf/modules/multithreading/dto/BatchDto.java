package com.udcf.modules.multithreading.dto;

import com.udcf.modules.multithreading.WorkloadType;

import java.util.List;

/**
 * Returned as soon as a burst of requests has been handed to a node; the work then drains in
 * the background and BATCH_FINISHED follows on the event stream.
 *
 * <p>No dedicated test: a data carrier. MultithreadingControllerTest asserts its JSON fields.</p>
 *
 * @param batchId    short id, also in the BATCH_SUBMITTED and BATCH_FINISHED events
 * @param nodeId     the node that received the burst
 * @param kind       BATCH or BACKPRESSURE
 * @param workload   the work each request does
 * @param payloadSize work units per request, before the node's work multiplier
 * @param requested  requests sent
 * @param accepted   requests the node queued or started
 * @param rejected   requests refused because the queue was full (or the node went down)
 * @param requestIds every request's id, in submission order
 */
public record BatchDto(
        String batchId,
        int nodeId,
        BatchKind kind,
        WorkloadType workload,
        int payloadSize,
        int requested,
        int accepted,
        int rejected,
        List<String> requestIds
) {
}
