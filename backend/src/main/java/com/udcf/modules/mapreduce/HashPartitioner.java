package com.udcf.modules.mapreduce;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hash partitioner distributing keys across reducers using {@code Math.floorMod(key.hashCode(), R)}.
 */
public final class HashPartitioner {

    private HashPartitioner() { }

    /**
     * Calculates the target reducer partition index for {@code key}.
     */
    public static int partition(String key, int numReducers) {
        if (numReducers <= 0) {
            throw new IllegalArgumentException("numReducers must be positive: " + numReducers);
        }
        Objects.requireNonNull(key, "key must not be null");
        return Math.floorMod(key.hashCode(), numReducers);
    }

    /**
     * Partitions grouped key-value collections across {@code numReducers} partition maps.
     */
    public static List<Map<String, List<String>>> partition(
            Map<String, List<String>> groupedKeys, int numReducers) {
        if (numReducers <= 0) {
            throw new IllegalArgumentException("numReducers must be positive: " + numReducers);
        }
        Objects.requireNonNull(groupedKeys, "groupedKeys must not be null");

        List<Map<String, List<String>>> partitions = new ArrayList<>(numReducers);
        for (int i = 0; i < numReducers; i++) {
            partitions.add(new LinkedHashMap<>());
        }

        for (Map.Entry<String, List<String>> entry : groupedKeys.entrySet()) {
            int p = partition(entry.getKey(), numReducers);
            partitions.get(p).put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        return partitions;
    }
}
