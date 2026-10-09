package com.udcf.modules.mapreduce;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Pure execution of a reduce task and partition payload serialization.
 *
 * <p>Uses Base64 encoding per field so any keys and values (including tabs, newlines,
 * {@code \u0001}, {@code =}, {@code #}, and non-ASCII text) round-trip identically.</p>
 */
public final class ReduceTask {

    private ReduceTask() { }

    /**
     * Executes reduce for each key in {@code partition} using {@code job}.
     * Returns a {@link TreeMap} sorted by key.
     */
    public static Map<String, String> execute(
            MapReduceJob job, Map<String, List<String>> partition) {
        Objects.requireNonNull(job, "job must not be null");
        Objects.requireNonNull(partition, "partition must not be null");

        Map<String, String> results = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : partition.entrySet()) {
            String reduced = job.reduce(entry.getKey(), entry.getValue());
            results.put(entry.getKey(), reduced);
        }
        return results;
    }

    /**
     * Executes reduce on an encoded partition payload and returns an encoded result string.
     */
    public static String execute(MapReduceJob job, String rawPartitionText) {
        Map<String, List<String>> partition = decodePartition(rawPartitionText);
        Map<String, String> results = execute(job, partition);
        return encodeResult(results);
    }

    /**
     * Encodes a partition map into a serialized payload.
     * Each entry format: {@code <base64Key>\t<base64Val1>\u0001<base64Val2>...\n}
     */
    public static String encodePartition(Map<String, List<String>> partition) {
        Objects.requireNonNull(partition, "partition must not be null");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : partition.entrySet()) {
            sb.append(encodeField(entry.getKey())).append('\t');
            List<String> values = entry.getValue();
            if (values != null && !values.isEmpty()) {
                for (int i = 0; i < values.size(); i++) {
                    if (i > 0) {
                        sb.append('\u0001');
                    }
                    sb.append(encodeField(values.get(i)));
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Decodes a serialized partition payload back into a partition map.
     */
    public static Map<String, List<String>> decodePartition(String payload) {
        Map<String, List<String>> partition = new TreeMap<>();
        if (payload == null || payload.isEmpty()) {
            return partition;
        }

        String[] lines = payload.split("\r?\n");
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab < 0) {
                throw new IllegalArgumentException("Malformed partition line: " + line);
            }
            String key = decodeField(line.substring(0, tab));
            String valSegment = line.substring(tab + 1);
            List<String> values = new ArrayList<>();
            if (!valSegment.isEmpty()) {
                String[] parts = valSegment.split("\u0001", -1);
                for (String p : parts) {
                    values.add(decodeField(p));
                }
            }
            partition.computeIfAbsent(key, k -> new ArrayList<>()).addAll(values);
        }

        return partition;
    }

    /**
     * Encodes a reduce results map into a serialized payload.
     * Format: {@code <base64Key>\t<base64Val>\n}
     */
    public static String encodeResult(Map<String, String> results) {
        Objects.requireNonNull(results, "results must not be null");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : results.entrySet()) {
            sb.append(encodeField(entry.getKey()))
              .append('\t')
              .append(encodeField(entry.getValue()))
              .append('\n');
        }
        return sb.toString();
    }

    /**
     * Decodes a serialized result payload back into a sorted results map.
     */
    public static Map<String, String> decodeResult(String payload) {
        Map<String, String> results = new TreeMap<>();
        if (payload == null || payload.isEmpty()) {
            return results;
        }

        String[] lines = payload.split("\r?\n");
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab < 0) {
                throw new IllegalArgumentException("Malformed result line: " + line);
            }
            String key = decodeField(line.substring(0, tab));
            String value = decodeField(line.substring(tab + 1));
            results.put(key, value);
        }

        return results;
    }

    private static String encodeField(String field) {
        return Base64.getEncoder().encodeToString(field.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeField(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}
