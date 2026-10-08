package com.udcf.modules.replication;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A replica's whole store as read over TCP, page by page ({@link ReplicationClient#dump}).
 *
 * <p>The pages are separate exchanges, so a dump taken while writes are arriving is not one
 * atomic snapshot: each key is exactly as the replica held it when its page was read.</p>
 *
 * <p>Covered by ReplicationRecordsTest (validation) and ReplicationClientTest.</p>
 *
 * @param nodeId     the replica, at least 1
 * @param storeEpoch the replica's store epoch when the last page was read, at least 1
 * @param items      every item by key; copied, unmodifiable, key order
 */
public record ReplicaDump(int nodeId, long storeEpoch, SortedMap<String, DataItem> items) {

    public ReplicaDump {
        ReplyChecks.header(nodeId, 0, storeEpoch);
        Objects.requireNonNull(items, "items must not be null");
        for (Map.Entry<String, DataItem> entry : items.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().key())) {
                throw new IllegalArgumentException("entry for key " + entry.getKey() + " holds the item for key "
                        + entry.getValue().key());
            }
        }
        items = Collections.unmodifiableSortedMap(new TreeMap<>(items));
    }
}
