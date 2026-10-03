package com.udcf.fault;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The replicated system-configuration store held by every node.
 *
 * <p>One method guards every write, whether it came from a client on the primary or from
 * a replication message on a backup. An update is stored only if it is newer; anything
 * older is discarded rather than allowed to overwrite good data.</p>
 */
public class UpdateStore {

    private final Map<String, SystemUpdate> items = new ConcurrentHashMap<>();

    /**
     * Applies an update if it is newer than what is held.
     *
     * <p>{@code compute} makes the read, the comparison and the write one atomic step, so
     * two connection threads cannot interleave and let a stale value win.</p>
     */
    public boolean apply(SystemUpdate incoming) {
        final boolean[] stored = { false };
        items.compute(incoming.key(), (k, existing) -> {
            if (incoming.isNewerThan(existing)) {
                stored[0] = true;
                return incoming;
            }
            return existing;
        });
        return stored[0];
    }

    public Optional<SystemUpdate> get(String key) {
        return Optional.ofNullable(items.get(key));
    }

    public boolean contains(String key) {
        return items.containsKey(key);
    }

    public Map<String, SystemUpdate> snapshot() {
        return new TreeMap<>(items);
    }

    public int size() {
        return items.size();
    }

    public String encodeAll() {
        StringBuilder sb = new StringBuilder();
        for (SystemUpdate u : snapshot().values()) {
            if (sb.length() > 0) {
                sb.append('~');
            }
            sb.append(u.encode());
        }
        return sb.toString();
    }
}
