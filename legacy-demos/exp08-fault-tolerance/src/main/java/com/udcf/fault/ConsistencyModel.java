package com.udcf.fault;

/**
 * When the primary tells the client an update succeeded.
 *
 * <p>This one choice decides how much data a crash can destroy, which is exactly what
 * this experiment measures.</p>
 */
public enum ConsistencyModel {

    /**
     * The primary replicates to the backups and waits for their acknowledgements before
     * confirming to the client. A confirmed update is therefore already on another
     * machine, so losing the primary loses nothing.
     */
    SYNCHRONOUS,

    /**
     * The primary applies the update locally, confirms to the client at once, and
     * replicates afterwards. Faster for the client, but any update still travelling when
     * the primary dies is gone — and the client was already told it succeeded.
     */
    ASYNCHRONOUS
}
