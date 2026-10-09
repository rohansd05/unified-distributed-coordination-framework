package com.udcf.modules.mapreduce;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordCountJobTest {

    @Test
    void testMetadata() {
        WordCountJob job = new WordCountJob();
        assertEquals("word-count", job.name());
        assertEquals("How many times does each word appear?", job.description());
        assertTrue(job.usesCombiner());
    }

    @Test
    void testTokenizationAndMapping() {
        WordCountJob job = new WordCountJob();
        List<KeyValuePair> emitted = new ArrayList<>();
        job.map("Hello, WORLD! Hello 123.", (k, v) -> emitted.add(new KeyValuePair(k, v)));

        assertEquals(4, emitted.size());
        assertEquals("hello", emitted.get(0).key());
        assertEquals("1", emitted.get(0).value());
        assertEquals("world", emitted.get(1).key());
        assertEquals("1", emitted.get(1).value());
        assertEquals("hello", emitted.get(2).key());
        assertEquals("1", emitted.get(2).value());
        assertEquals("123", emitted.get(3).key());
        assertEquals("1", emitted.get(3).value());
    }

    @Test
    void testCombineAndReduce() {
        WordCountJob job = new WordCountJob();
        assertEquals("6", job.combine("word", List.of("1", "2", "3")));
        assertEquals("6", job.reduce("word", List.of("1", "2", "3")));
        assertEquals("1", job.combine("word", List.of("1")));
        assertEquals("0", job.combine("word", List.of()));
    }

    @Test
    void testCombineAssociativity() {
        WordCountJob job = new WordCountJob();
        // combine(combine(a, b), c) == combine(a, combine(b, c))
        String ab = job.combine("test", List.of("2", "3"));
        String ab_c = job.combine("test", List.of(ab, "5"));

        String bc = job.combine("test", List.of("3", "5"));
        String a_bc = job.combine("test", List.of("2", bc));

        assertEquals("10", ab_c);
        assertEquals(ab_c, a_bc);
    }

    @Test
    void testTurkishLocaleInvariance() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            WordCountJob job = new WordCountJob();
            List<KeyValuePair> emitted = new ArrayList<>();
            job.map("INITIAL Title", (k, v) -> emitted.add(new KeyValuePair(k, v)));

            // Under Locale.ROOT, 'I' becomes 'i' (U+0069), not Turkish dotless 'ı' (U+0131)
            assertEquals(2, emitted.size());
            assertEquals("initial", emitted.get(0).key());
            assertEquals("title", emitted.get(1).key());
        } finally {
            Locale.setDefault(original);
        }
    }
}
