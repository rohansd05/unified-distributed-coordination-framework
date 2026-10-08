package com.udcf.modules.replication;

import java.util.Objects;

/**
 * A replica's reply to one REPLICATE push.
 *
 * <p>Covered by ReplicationRecordsTest (validation) and ReplicationProtocolTest.</p>
 *
 * @param nodeId      the replica that answered, at least 1
 * @param lamportTime the replica's Lamport time when it replied (link L4), at least 0
 * @param storeEpoch  the replica's store epoch after the apply, at least 1
 * @param result      what the replica's {@link DataStore#apply(DataItem, long)} did
 */
public record AckReply(int nodeId, long lamportTime, long storeEpoch, ApplyResult result) {

    public AckReply {
        ReplyChecks.header(nodeId, lamportTime, storeEpoch);
        Objects.requireNonNull(result, "result must not be null");
    }
}
