package com.udcf.modules.mapreduce;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventCategoryJobTest {

    @Test
    void testMetadata() {
        EventCategoryJob job = new EventCategoryJob();
        assertEquals("event-category-count", job.name());
        assertEquals("How many events of each category did the cluster produce?", job.description());
        assertTrue(job.usesCombiner());
    }

    @Test
    void testMappingValidLine() {
        EventCategoryJob job = new EventCategoryJob();
        List<KeyValuePair> emitted = new ArrayList<>();
        job.map("2026-09-18 14:33:28.151 | node=2 | category=RECV | latency=10.48 | msg",
                (k, v) -> emitted.add(new KeyValuePair(k, v)));

        assertEquals(1, emitted.size());
        assertEquals("RECV", emitted.get(0).key());
        assertEquals("1", emitted.get(0).value());
    }

    @Test
    void testMalformedLineSilentlySkipped() {
        EventCategoryJob job = new EventCategoryJob();
        List<KeyValuePair> emitted = new ArrayList<>();
        job.map("unparseable line without category", (k, v) -> emitted.add(new KeyValuePair(k, v)));
        job.map("", (k, v) -> emitted.add(new KeyValuePair(k, v)));
        job.map(null, (k, v) -> emitted.add(new KeyValuePair(k, v)));

        assertTrue(emitted.isEmpty());
    }

    @Test
    void testCombineAndReduce() {
        EventCategoryJob job = new EventCategoryJob();
        assertEquals("12", job.combine("RECV", List.of("5", "4", "3")));
        assertEquals("12", job.reduce("RECV", List.of("5", "4", "3")));
    }

    @Test
    void testCombineAssociativity() {
        EventCategoryJob job = new EventCategoryJob();
        String ab = job.combine("SEND", List.of("4", "6"));
        String ab_c = job.combine("SEND", List.of(ab, "10"));

        String bc = job.combine("SEND", List.of("6", "10"));
        String a_bc = job.combine("SEND", List.of("4", bc));

        assertEquals("20", ab_c);
        assertEquals(ab_c, a_bc);
    }
}
