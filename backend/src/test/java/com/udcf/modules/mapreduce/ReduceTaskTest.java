package com.udcf.modules.mapreduce;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReduceTaskTest {

    @Test
    void testReduceExecution() {
        WordCountJob job = new WordCountJob();
        Map<String, List<String>> partition = new TreeMap<>();
        partition.put("apple", List.of("2", "3"));
        partition.put("banana", List.of("1"));

        Map<String, String> results = ReduceTask.execute(job, partition);
        assertEquals(2, results.size());
        assertEquals("5", results.get("apple"));
        assertEquals("1", results.get("banana"));
    }

    @Test
    void testPartitionEncodeDecodeSpecialCharacters() {
        // Round-trip tab, newline, carriage return, \u0001, '=', '#', empty strings, non-ASCII
        Map<String, List<String>> partition = new TreeMap<>();
        partition.put("key\twith\ttab", List.of("v1\twith\ttab", "v2\nnewline"));
        partition.put("key\r\ncrlf", List.of("val\u0001with\u0001control", "val=equal#hash"));
        partition.put("", List.of("", "non_empty"));
        partition.put("日本語キー", List.of("café", "🚀"));

        String encoded = ReduceTask.encodePartition(partition);
        Map<String, List<String>> decoded = ReduceTask.decodePartition(encoded);

        assertEquals(partition.size(), decoded.size());
        for (String k : partition.keySet()) {
            assertEquals(partition.get(k), decoded.get(k), "Values mismatch for key: " + k);
        }
    }

    @Test
    void testResultEncodeDecodeSpecialCharacters() {
        Map<String, String> results = new TreeMap<>();
        results.put("key\twith\ttab", "val\twith\ttab");
        results.put("key\nnewline", "val\r\ncrlf");
        results.put("key\u0001control", "val#=hash_equals");
        results.put("", "");
        results.put("日本語", "val_café_🎉");

        String encoded = ReduceTask.encodeResult(results);
        Map<String, String> decoded = ReduceTask.decodeResult(encoded);

        assertEquals(results.size(), decoded.size());
        for (String k : results.keySet()) {
            assertEquals(results.get(k), decoded.get(k), "Value mismatch for key: " + k);
        }
    }

    @Test
    void testMalformedPartitionPayloadThrows() {
        String bad = "line_without_tab";
        assertThrows(IllegalArgumentException.class, () -> ReduceTask.decodePartition(bad));
    }

    @Test
    void testMalformedResultPayloadThrows() {
        String bad = "result_line_without_tab";
        assertThrows(IllegalArgumentException.class, () -> ReduceTask.decodeResult(bad));
    }
}
