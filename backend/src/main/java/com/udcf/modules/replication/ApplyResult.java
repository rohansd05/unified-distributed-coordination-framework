package com.udcf.modules.replication;

/**
 * What {@link DataStore#apply(DataItem, long)} did with one update.
 *
 * <p>Every attempt ends in exactly one of these, so the store's four counters always add
 * up to the number of attempts.</p>
 *
 * <p>No dedicated test: an enum whose only method is covered by {@code DataStoreTest} (R6).</p>
 */
public enum ApplyResult {

    /** The update was newer than what the store held, and it is now stored. */
    APPLIED,

    /**
     * The store already holds exactly this item: the same epoch, Lamport time, origin and
     * value. Nothing changed. A redelivery or an anti-entropy push of an item the replica
     * already has ends here; it is not a conflict and is never counted as stale.
     */
    DUPLICATE,

    /**
     * The update lost on last-writer-wins: the store holds a newer version of the key.
     *
     * <p>Also returned, deliberately, for the same version with a <b>different</b> value. A
     * correct node never stamps two values with one (epoch, Lamport time, origin), so that
     * is a protocol fault; the stored value is kept and never overwritten (known limitation:
     * the fault is refused, not repaired).</p>
     */
    STALE,

    /**
     * The sender's epoch is below the store's epoch: the update comes from a superseded
     * primary and is refused without being compared.
     */
    STALE_EPOCH;

    /** True only for {@link #APPLIED}. */
    public boolean stored() {
        return this == APPLIED;
    }
}
