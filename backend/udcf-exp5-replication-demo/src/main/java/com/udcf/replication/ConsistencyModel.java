package com.udcf.replication;

/**
 * The two replication models demonstrated in Experiment 5.
 *
 * <p>Both write the same data to the same replicas. They differ only in <b>when the
 * primary tells the client the write succeeded</b>, and that single difference is what
 * produces the classic consistency-versus-latency trade-off.</p>
 */
public enum ConsistencyModel {

    /**
     * Strong consistency. The primary replicates to every backup and blocks until each
     * one has acknowledged, only then confirming the write to the client.
     *
     * <p>Guarantee: once the client is told the write succeeded, a read from any replica
     * returns the new value. Cost: the client waits for the slowest backup.</p>
     */
    SYNCHRONOUS,

    /**
     * Eventual consistency. The primary applies the write locally, confirms to the client
     * immediately, and replicates to the backups on a background thread.
     *
     * <p>Guarantee: the replicas converge, but only eventually. Cost: there is a window
     * during which a read from a backup returns a stale value.</p>
     */
    ASYNCHRONOUS
}
