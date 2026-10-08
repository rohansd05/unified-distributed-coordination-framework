package com.udcf.modules.replication;

import java.util.Objects;

/**
 * A replica's reply to one SYNC (anti-entropy) message: what the merge of that batch did.
 *
 * <p>Covered by ReplicationRecordsTest (validation) and ReplicationProtocolTest.</p>
 *
 * @param nodeId      the replica that answered, at least 1
 * @param lamportTime the replica's Lamport time when it replied (link L4), at least 0
 * @param storeEpoch  the replica's store epoch after the merge, at least 1
 * @param result      the merge counts for this batch
 */
public record SyncReply(int nodeId, long lamportTime, long storeEpoch, AntiEntropyResult result) {

    public SyncReply {
        ReplyChecks.header(nodeId, lamportTime, storeEpoch);
        Objects.requireNonNull(result, "result must not be null");
    }
}
