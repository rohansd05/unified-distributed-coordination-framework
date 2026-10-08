package com.udcf.modules.replication;

/**
 * Thrown when a node is asked to act as the replication primary but is not, or no longer is.
 *
 * <p>Three cases: the node was never made primary ({@link ReplicationNodeService#becomePrimary});
 * it asked to become primary at an epoch below one it already knows; or it was superseded,
 * having learned a higher epoch from a {@link ApplyResult#STALE_EPOCH} reply, from a push by a
 * newer primary, or through {@link ReplicationNodeService#observeEpoch}.</p>
 *
 * <p>A superseded <b>synchronous</b> write throws this after its round: the write is <b>not
 * confirmed</b>, but "not confirmed" does <b>not</b> mean "not stored". The item was already
 * applied to the old primary's own store, and possibly to backups that had not yet seen the
 * newer epoch.</p>
 *
 * <p>No dedicated test: a constructor and accessors only; thrown and checked in
 * ReplicationNodeServiceTest.</p>
 */
public class NotPrimaryException extends RuntimeException {

    private final int nodeId;
    private final long storeEpoch;

    public NotPrimaryException(int nodeId, long storeEpoch, String message) {
        super(message);
        this.nodeId = nodeId;
        this.storeEpoch = storeEpoch;
    }

    public int nodeId() {
        return nodeId;
    }

    /** The node's store epoch when this was thrown: the highest epoch it knows. */
    public long storeEpoch() {
        return storeEpoch;
    }
}
