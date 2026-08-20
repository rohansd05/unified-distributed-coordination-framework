package com.udcf.model;

/**
 * The kind of work a request performs.
 *
 * <p>These do real work rather than sleeping arbitrarily, so the throughput and
 * response times shown in the dashboard reflect genuine executor behaviour.</p>
 *
 * <p>No dedicated test file: enum constants only. Behaviour lives in WorkloadExecutor
 * and is covered by WorkloadExecutorTest.</p>
 */
public enum WorkloadType {

    /** CPU-bound: repeated SHA-256 hashing. Saturates cores, so pool size matters. */
    CPU_HASH,

    /** IO-bound: blocking wait. Threads sit idle, so queueing behaviour is visible. */
    IO_SIMULATED,

    /** Both, in sequence — closest to a realistic distributed request. */
    MIXED
}
