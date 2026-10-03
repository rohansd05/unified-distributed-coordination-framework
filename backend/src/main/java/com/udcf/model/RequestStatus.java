package com.udcf.model;

/**
 * Lifecycle of a request inside a node's thread pool.
 *
 * <p>No dedicated test file: a simple enum with no behaviour. Its transitions are
 * asserted in DistributedRequestTest and RequestProcessingServiceTest.</p>
 */
public enum RequestStatus {

    /** Accepted by the pool and waiting in the queue. */
    QUEUED,

    /** Picked up by a worker thread and executing. */
    PROCESSING,

    /** Finished successfully. */
    COMPLETED,

    /** The workload threw an exception. */
    FAILED,

    /** Refused because the bounded queue was full — this is backpressure, not an error. */
    REJECTED
}
