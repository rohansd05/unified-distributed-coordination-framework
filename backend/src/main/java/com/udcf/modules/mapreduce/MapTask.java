package com.udcf.modules.mapreduce;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure execution of a map task, including optional local combiner and serialization.
 *
 * <p>Payload encoding uses Base64 per field to safely round-trip any characters
 * (tabs, newlines, carriage returns, {@code \u0001}, {@code =}, {@code #}, empty strings,
 * and non-ASCII text) across network and pipeline boundaries without escaping issues.</p>
 */
public final class MapTask {

    private MapTask() { }

    /**
     * Typed result of a map task execution containing raw pair count and combined pairs.
     */
    public record Result(long rawPairsCount, List<KeyValuePair> pairs) {
        public Result {
            Objects.requireNonNull(pairs, "pairs must not be null");
        }
    }

    /**
     * Executes map (and optional local combiner) on a list of input lines.
     */
    public static Result execute(MapReduceJob job, List<String> lines) {
        Objects.requireNonNull(job, "job must not be null");
        Objects.requireNonNull(lines, "lines must not be null");

        List<KeyValuePair> emitted = new ArrayList<>();
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                job.map(line, (k, v) -> emitted.add(new KeyValuePair(k, v)));
            }
        }

        long rawCount = emitted.size();

        if (!job.usesCombiner()) {
            return new Result(rawCount, emitted);
        }

        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (KeyValuePair pair : emitted) {
            grouped.computeIfAbsent(pair.key(), k -> new ArrayList<>()).add(pair.value());
        }

        List<KeyValuePair> combined = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            String combinedVal = job.combine(entry.getKey(), entry.getValue());
            combined.add(new KeyValuePair(entry.getKey(), combinedVal));
        }

        return new Result(rawCount, combined);
    }

    /**
     * Executes map on a raw text split (lines delimited by newline) and returns the encoded payload.
     */
    public static String execute(MapReduceJob job, String rawSplitText) {
        if (rawSplitText == null || rawSplitText.isEmpty()) {
            return encode(new Result(0, List.of()));
        }
        String[] lineArray = rawSplitText.split("\r?\n");
        List<String> lines = List.of(lineArray);
        Result result = execute(job, lines);
        return encode(result);
    }

    /**
     * Encodes a {@link Result} into a serialized string payload.
     */
    public static String encode(Result result) {
        Objects.requireNonNull(result, "result must not be null");
        StringBuilder sb = new StringBuilder();
        sb.append("#raw=").append(result.rawPairsCount()).append('\n');
        for (KeyValuePair pair : result.pairs()) {
            sb.append(encodeField(pair.key()))
              .append('\t')
              .append(encodeField(pair.value()))
              .append('\n');
        }
        return sb.toString();
    }

    /**
     * Decodes a serialized string payload back into a {@link Result}.
     */
    public static Result decode(String payload) {
        if (payload == null || payload.isEmpty()) {
            return new Result(0, new ArrayList<>());
        }

        long rawCount = 0;
        List<KeyValuePair> pairs = new ArrayList<>();
        String[] lines = payload.split("\r?\n");

        for (String line : lines) {
            if (line.startsWith("#raw=")) {
                rawCount = Long.parseLong(line.substring(5).trim());
                continue;
            }
            if (line.isEmpty()) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab < 0) {
                throw new IllegalArgumentException("Malformed map output line: " + line);
            }
            String key = decodeField(line.substring(0, tab));
            String value = decodeField(line.substring(tab + 1));
            pairs.add(new KeyValuePair(key, value));
        }

        return new Result(rawCount, pairs);
    }

    private static String encodeField(String field) {
        return Base64.getEncoder().encodeToString(field.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeField(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}
