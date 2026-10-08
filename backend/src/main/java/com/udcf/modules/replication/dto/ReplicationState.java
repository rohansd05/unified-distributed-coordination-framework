package com.udcf.modules.replication.dto;

/**
 * Whether a write's pushes to the backups have all ended.
 *
 * <p>No dedicated test: an enum without behaviour (R6).</p>
 */
public enum ReplicationState {

    /** Asynchronous: confirmed to the client, pushes still waiting or in flight. */
    PENDING,

    /** Every push has ended (acknowledged, failed or dropped). */
    COMPLETE
}
