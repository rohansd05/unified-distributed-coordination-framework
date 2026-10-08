package com.udcf.modules.replication;

import java.util.Objects;
import java.util.Optional;

/**
 * A replica's reply to a READ: the version of the key it holds right now, if any.
 *
 * <p>Covered by ReplicationRecordsTest (validation) and ReplicationProtocolTest.</p>
 *
 * @param nodeId      the replica that answered, at least 1
 * @param lamportTime the replica's Lamport time when it replied (link L4), at least 0
 * @param storeEpoch  the replica's store epoch, at least 1
 * @param item        the stored version, or empty if the replica does not hold the key
 */
public record ReadReply(int nodeId, long lamportTime, long storeEpoch, Optional<DataItem> item) {

    public ReadReply {
        ReplyChecks.header(nodeId, lamportTime, storeEpoch);
        Objects.requireNonNull(item, "item must not be null (use Optional.empty())");
    }
}
