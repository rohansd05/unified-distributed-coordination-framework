package com.udcf.modules.replication;

/**
 * The header checks every replication reply record shares.
 *
 * <p>No dedicated test: one static check, covered through each record by ReplicationRecordsTest.</p>
 */
final class ReplyChecks {

    private ReplyChecks() {
    }

    static void header(int nodeId, long lamportTime, long storeEpoch) {
        if (nodeId < 1) {
            throw new IllegalArgumentException("nodeId must be >= 1, was " + nodeId);
        }
        if (lamportTime < 0) {
            throw new IllegalArgumentException("lamportTime must be >= 0, was " + lamportTime);
        }
        if (storeEpoch < DataStore.INITIAL_EPOCH) {
            throw new IllegalArgumentException("storeEpoch must be >= " + DataStore.INITIAL_EPOCH
                    + ", was " + storeEpoch);
        }
    }
}
