package com.udcf.replication;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The replicated key-value store held by every node.
 *
 * <p>This is where the consistency rule actually lives. {@link #apply(DataItem)} is the
 * single entry point for every update, whether it came from a local client write on the
 * primary or from a replication message on a backup, and it refuses any update that is
 * not newer than what is already stored.</p>
 *
 * <p>That refusal is what makes the system tolerate out-of-order delivery. Two updates to
 * the same key can arrive at a backup in the wrong order; the older one is discarded
 * instead of overwriting the newer value.</p>
 */
public class DataStore {

    private final Map<String, DataItem> items = new ConcurrentHashMap<>();
    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    /**
     * Applies an update if and only if it is newer than the current version.
     *
     * <p>{@code compute} performs the read, the comparison and the write as one atomic
     * operation. Doing them as three separate steps would let two threads interleave and
     * allow a stale value to win, which is precisely the bug this rule exists to prevent.</p>
     *
     * @return true if the item was stored, false if it was rejected as stale
     */
    public boolean apply(DataItem incoming) {
        final boolean[] stored = { false };
        items.compute(incoming.key(), (key, existing) -> {
            if (incoming.isNewerThan(existing)) {
                stored[0] = true;
                return incoming;
            }
            return existing;
        });
        if (stored[0]) {
            applied.incrementAndGet();
        } else {
            rejected.incrementAndGet();
        }
        return stored[0];
    }

    public Optional<DataItem> get(String key) {
        return Optional.ofNullable(items.get(key));
    }

    /** Sorted so two replicas can be compared line by line. */
    public Map<String, DataItem> snapshot() {
        return new TreeMap<>(items);
    }

    public int size() {
        return items.size();
    }

    public long appliedCount() {
        return applied.get();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    /** Serialised form of the whole store, used to compare replicas at the end of a run. */
    public String encodeAll() {
        StringBuilder sb = new StringBuilder();
        for (DataItem item : snapshot().values()) {
            if (sb.length() > 0) {
                sb.append('~');
            }
            sb.append(item.encode());
        }
        return sb.toString();
    }
}
