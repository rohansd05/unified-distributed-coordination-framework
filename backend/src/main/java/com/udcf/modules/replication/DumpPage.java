package com.udcf.modules.replication;

import java.util.List;
import java.util.Objects;

/**
 * One page of a replica's store, in key order, from a DUMP request.
 *
 * <p>Covered by ReplicationRecordsTest (validation) and ReplicationProtocolTest.</p>
 *
 * @param nodeId      the replica that answered, at least 1
 * @param lamportTime the replica's Lamport time when it replied (link L4), at least 0
 * @param storeEpoch  the replica's store epoch, at least 1
 * @param items       at most {@link ReplicationProtocol#MAX_ITEMS_PER_MESSAGE} items, keys
 *                    strictly ascending; copied
 * @param more        true if the replica holds keys after the last one in this page
 */
public record DumpPage(int nodeId, long lamportTime, long storeEpoch, List<DataItem> items, boolean more) {

    public DumpPage {
        ReplyChecks.header(nodeId, lamportTime, storeEpoch);
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        if (items.size() > ReplicationProtocol.MAX_ITEMS_PER_MESSAGE) {
            throw new IllegalArgumentException("a page holds at most " + ReplicationProtocol.MAX_ITEMS_PER_MESSAGE
                    + " items, was " + items.size());
        }
        for (int i = 1; i < items.size(); i++) {
            if (items.get(i - 1).key().compareTo(items.get(i).key()) >= 0) {
                throw new IllegalArgumentException("page keys must be strictly ascending (index " + i + ")");
            }
        }
    }
}
