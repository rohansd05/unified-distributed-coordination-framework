package com.udcf.modules.mapreduce;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobRegistryTest {

    @Test
    void testStandardJobsRegistered() {
        JobRegistry registry = JobRegistry.standard();
        assertTrue(registry.contains("word-count"));
        assertTrue(registry.contains("event-category-count"));
        assertTrue(registry.contains("avg-latency-per-node"));

        assertNotNull(registry.get("word-count"));
        assertNotNull(registry.get("event-category-count"));
        assertNotNull(registry.get("avg-latency-per-node"));

        assertEquals(3, registry.all().size());
        assertEquals(3, registry.names().size());
    }

    @Test
    void testUnknownJobThrows() {
        JobRegistry registry = JobRegistry.standard();
        assertThrows(IllegalArgumentException.class, () -> registry.get("non-existent"));
        assertThrows(IllegalArgumentException.class, () -> registry.get(null));
        assertFalse(registry.contains("non-existent"));
        assertFalse(registry.contains(null));
    }

    @Test
    void testCustomJobRegistration() {
        JobRegistry registry = new JobRegistry();
        MapReduceJob customJob = new MapReduceJob() {
            @Override public String name() { return "custom"; }
            @Override public String description() { return "custom description"; }
            @Override public void map(String line, BiConsumer<String, String> emit) { emit.accept("k", "v"); }
            @Override public String combine(String key, List<String> values) { return "combined"; }
            @Override public String reduce(String key, List<String> values) { return "reduced"; }
        };

        registry.register(customJob);
        assertTrue(registry.contains("custom"));
        assertEquals("custom", registry.get("custom").name());
    }
}
