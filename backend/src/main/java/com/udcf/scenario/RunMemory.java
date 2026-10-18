package com.udcf.scenario;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Values one run's steps share: what an earlier step observed (the leader, an epoch) and the
 * event anchors of its actions. Thread-safe; one per run.
 */
public final class RunMemory {

    private final Map<String, Object> values = new ConcurrentHashMap<>();

    /** Stores a value; null removes it. */
    public void remember(String key, Object value) {
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
    }

    public <T> Optional<T> find(String key, Class<T> type) {
        Object value = values.get(key);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    /** @throws IllegalStateException if no earlier step stored a value of this type under {@code key} */
    public <T> T recall(String key, Class<T> type) {
        return find(key, type).orElseThrow(() -> new IllegalStateException(
                "No earlier step recorded '" + key + "'"));
    }
}
