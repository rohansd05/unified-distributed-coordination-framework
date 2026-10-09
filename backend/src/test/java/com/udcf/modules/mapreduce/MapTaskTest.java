package com.udcf.modules.mapreduce;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapTaskTest {

    @Test
    void testMapWithCombiner() {
        WordCountJob job = new WordCountJob();
        List<String> lines = List.of("apple orange", "apple banana");

        MapTask.Result result = MapTask.execute(job, lines);
        assertEquals(4, result.rawPairsCount()); // apple, orange, apple, banana
        assertEquals(3, result.pairs().size());  // apple=2, orange=1, banana=1

        KeyValuePair applePair = result.pairs().stream()
                .filter(p -> p.key().equals("apple"))
                .findFirst()
                .orElseThrow();
        assertEquals("2", applePair.value());
    }

    @Test
    void testMapWithoutCombiner() {
        MapReduceJob noCombinerJob = new MapReduceJob() {
            @Override public String name() { return "no-combine"; }
            @Override public String description() { return "no combine"; }
            @Override public void map(String line, BiConsumer<String, String> emit) {
                emit.accept("k", "1");
                emit.accept("k", "2");
            }
            @Override public String combine(String key, List<String> values) { return "combined"; }
            @Override public String reduce(String key, List<String> values) { return "reduced"; }
            @Override public boolean usesCombiner() { return false; }
        };

        MapTask.Result result = MapTask.execute(noCombinerJob, List.of("line"));
        assertEquals(2, result.rawPairsCount());
        assertEquals(2, result.pairs().size());
        assertEquals("1", result.pairs().get(0).value());
        assertEquals("2", result.pairs().get(1).value());
    }

    @Test
    void testEncodeDecodeRoundTripStandard() {
        List<KeyValuePair> pairs = List.of(
                new KeyValuePair("alpha", "10"),
                new KeyValuePair("beta", "20")
        );
        MapTask.Result original = new MapTask.Result(50, pairs);

        String encoded = MapTask.encode(original);
        MapTask.Result decoded = MapTask.decode(encoded);

        assertEquals(original.rawPairsCount(), decoded.rawPairsCount());
        assertEquals(original.pairs().size(), decoded.pairs().size());
        assertEquals("alpha", decoded.pairs().get(0).key());
        assertEquals("10", decoded.pairs().get(0).value());
        assertEquals("beta", decoded.pairs().get(1).key());
        assertEquals("20", decoded.pairs().get(1).value());
    }

    @Test
    void testEncodeDecodeSpecialCharactersRoundTrip() {
        // Must round-trip tab, newline, carriage return, \u0001, '=', '#', empty strings, and non-ASCII text
        List<KeyValuePair> pairs = List.of(
                new KeyValuePair("key\twith\ttab", "val\twith\ttab"),
                new KeyValuePair("key\nwith\nnewline", "val\r\nwith\rcrlf"),
                new KeyValuePair("key\u0001with\u0001control", "val\u0001control"),
                new KeyValuePair("key=with=equals", "val#with#hash"),
                new KeyValuePair("", "empty_key"),
                new KeyValuePair("empty_val", ""),
                new KeyValuePair("", ""),
                new KeyValuePair("日本語キー", "café naïve 🚀")
        );
        MapTask.Result original = new MapTask.Result(100, pairs);

        String encoded = MapTask.encode(original);
        MapTask.Result decoded = MapTask.decode(encoded);

        assertEquals(original.rawPairsCount(), decoded.rawPairsCount());
        assertEquals(original.pairs().size(), decoded.pairs().size());

        for (int i = 0; i < pairs.size(); i++) {
            assertEquals(pairs.get(i).key(), decoded.pairs().get(i).key(), "Key mismatch at index " + i);
            assertEquals(pairs.get(i).value(), decoded.pairs().get(i).value(), "Value mismatch at index " + i);
        }
    }

    @Test
    void testRejectMalformedPayload() {
        String badPayload = "#raw=10\nline_without_tab_delimiter\n";
        assertThrows(IllegalArgumentException.class, () -> MapTask.decode(badPayload));
    }
}
