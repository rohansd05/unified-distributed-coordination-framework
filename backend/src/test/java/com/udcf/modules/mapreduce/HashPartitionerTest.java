package com.udcf.modules.mapreduce;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashPartitionerTest {

    @Test
    void testPartitionBoundsAndDeterminism() {
        int numReducers = 5;
        for (int i = 0; i < 100; i++) {
            String key = "test_key_" + i;
            int p1 = HashPartitioner.partition(key, numReducers);
            int p2 = HashPartitioner.partition(key, numReducers);
            assertEquals(p1, p2, "Partitioner must be deterministic for identical key");
            assertTrue(p1 >= 0 && p1 < numReducers, "Partition must be within [0, numReducers - 1]");
        }
    }

    @Test
    void testNegativeHashCodeHandled() {
        String negKey = null;
        for (int i = 0; i < 10000; i++) {
            String candidate = "key_" + i;
            if (candidate.hashCode() < 0) {
                negKey = candidate;
                break;
            }
        }
        assertNotNull(negKey, "A key with negative hashCode must be found");
        assertTrue(negKey.hashCode() < 0, "negKey should have a negative hashCode");

        int p = HashPartitioner.partition(negKey, 3);
        assertTrue(p >= 0 && p < 3, "Partition index must be non-negative even for negative hashCode");
    }

    @Test
    void testPartitionAllGroupedKeys() {
        Map<String, List<String>> grouped = new TreeMap<>();
        grouped.put("apple", List.of("1", "2"));
        grouped.put("banana", List.of("3"));
        grouped.put("cherry", List.of("4", "5"));

        List<Map<String, List<String>>> partitions = HashPartitioner.partition(grouped, 3);
        assertEquals(3, partitions.size());

        int totalEntries = 0;
        for (Map<String, List<String>> partition : partitions) {
            totalEntries += partition.size();
        }
        assertEquals(3, totalEntries);
    }

    @Test
    void testInvalidNumReducersThrows() {
        assertThrows(IllegalArgumentException.class, () -> HashPartitioner.partition("k", 0));
        assertThrows(IllegalArgumentException.class, () -> HashPartitioner.partition("k", -1));
    }
}
