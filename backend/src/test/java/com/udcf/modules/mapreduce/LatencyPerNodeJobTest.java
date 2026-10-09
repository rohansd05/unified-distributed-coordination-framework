package com.udcf.modules.mapreduce;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyPerNodeJobTest {

    @Test
    void testMetadata() {
        LatencyPerNodeJob job = new LatencyPerNodeJob();
        assertEquals("avg-latency-per-node", job.name());
        assertEquals("What is the average event latency on each node?", job.description());
        assertTrue(job.usesCombiner());
    }

    @Test
    void testMappingEmitsSumAndCount() {
        LatencyPerNodeJob job = new LatencyPerNodeJob();
        List<KeyValuePair> emitted = new ArrayList<>();
        job.map("2026-09-18 14:33:28.151 | node=2 | category=RECV | latency=10.48 | msg",
                (k, v) -> emitted.add(new KeyValuePair(k, v)));

        assertEquals(1, emitted.size());
        assertEquals("node-2", emitted.get(0).key());
        assertEquals("10.48;1", emitted.get(0).value());
    }

    @Test
    void testMalformedOrMissingLatencySkipped() {
        LatencyPerNodeJob job = new LatencyPerNodeJob();
        List<KeyValuePair> emitted = new ArrayList<>();
        job.map("node=2 | category=RECV | latency=not_a_number", (k, v) -> emitted.add(new KeyValuePair(k, v)));
        job.map("node=2 | category=RECV", (k, v) -> emitted.add(new KeyValuePair(k, v)));
        job.map("category=RECV | latency=10.0", (k, v) -> emitted.add(new KeyValuePair(k, v)));
        job.map(null, (k, v) -> emitted.add(new KeyValuePair(k, v)));

        assertTrue(emitted.isEmpty());
    }

    @Test
    void testCombineCarriesOnlySumAndCountNeverAverage() {
        LatencyPerNodeJob job = new LatencyPerNodeJob();
        String combined = job.combine("node-1", List.of("10.0;2", "20.0;3"));
        assertEquals("30.0;5", combined);
        // Ensure no division happened
        assertFalse(combined.contains("6.0"));
    }

    @Test
    void testCombineAssociativity() {
        LatencyPerNodeJob job = new LatencyPerNodeJob();
        // combine(combine(a, b), c) == combine(a, combine(b, c))
        String a = "10.5;2";
        String b = "5.5;1";
        String c = "4.0;1";

        String ab = job.combine("node-1", List.of(a, b));
        String ab_c = job.combine("node-1", List.of(ab, c));

        String bc = job.combine("node-1", List.of(b, c));
        String a_bc = job.combine("node-1", List.of(a, bc));

        assertEquals("20.0;4", ab_c);
        assertEquals(ab_c, a_bc);
    }

    @Test
    void testFormatResultComputesCorrectAverage() {
        LatencyPerNodeJob job = new LatencyPerNodeJob();
        String formatted = job.formatResult("node-2", "30.0;4");
        assertEquals("7.50 ms average over 4 events", formatted);
    }

    @Test
    void testFormatResultGuardsAgainstZeroCountAndMalformed() {
        LatencyPerNodeJob job = new LatencyPerNodeJob();
        assertEquals("No latency measured", job.formatResult("node-1", "0.0;0"));
        assertEquals("No latency measured", job.formatResult("node-1", ""));
        assertEquals("No latency measured", job.formatResult("node-1", null));
        assertEquals("No latency measured", job.formatResult("node-1", "invalid"));
        assertEquals("No latency measured", job.formatResult("node-1", "10.0;-2"));
    }

    @Test
    void testDistinguishRealZeroAverageFromNoData() {
        LatencyPerNodeJob job = new LatencyPerNodeJob();
        // Genuine 0 latency measurement with events observed
        assertEquals("0.00 ms average over 3 events", job.formatResult("node-1", "0.0;3"));
        assertEquals("0.00 ms average over 1 events", job.formatResult("node-1", "0.0;1"));

        // Absence of data / zero count returns fixed non-numeric constant
        assertEquals("No latency measured", job.formatResult("node-1", "0.0;0"));
        assertEquals("No latency measured", job.formatResult("node-1", null));
        assertEquals("No latency measured", job.formatResult("node-1", ""));
    }

    @Test
    void testFormatResultUnderTurkishLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            LatencyPerNodeJob job = new LatencyPerNodeJob();
            String formatted = job.formatResult("node-1", "10.48;1");
            // Under Locale.ROOT, decimal separator is dot, not comma
            assertEquals("10.48 ms average over 1 events", formatted);
        } finally {
            Locale.setDefault(original);
        }
    }
}
