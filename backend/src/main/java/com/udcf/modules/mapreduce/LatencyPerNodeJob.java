package com.udcf.modules.mapreduce;

import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Project-specific job: compute average event latency per cluster node.
 *
 * <p>Demonstrates an essential MapReduce design requirement: an average is <b>not</b>
 * associative. You cannot average a collection of averages and produce a mathematically
 * correct result. The job therefore carries a partial sum and count through map, combine,
 * and reduce stages as {@code sum;count}, and only performs division during final formatting.</p>
 */
public class LatencyPerNodeJob implements MapReduceJob {

    @Override
    public String name() {
        return "avg-latency-per-node";
    }

    @Override
    public String description() {
        return "What is the average event latency on each node?";
    }

    @Override
    public void map(String line, BiConsumer<String, String> emit) {
        String node = LogFields.field(line, "node");
        String latency = LogFields.field(line, "latency");
        if (node == null || latency == null) {
            return;
        }
        try {
            double value = Double.parseDouble(latency);
            emit.accept("node-" + node, value + ";1");
        } catch (NumberFormatException ignored) {
            // Unparseable latency: skip record rather than aborting job
        }
    }

    /**
     * Adds partial sums and counts together.
     * Summing {@code (sum, count)} pairs is strictly associative.
     */
    @Override
    public String combine(String key, List<String> values) {
        double sum = 0.0;
        long count = 0;
        for (String v : values) {
            String[] parts = v.split(";");
            if (parts.length >= 2) {
                try {
                    sum += Double.parseDouble(parts[0]);
                    count += Long.parseLong(parts[1]);
                } catch (NumberFormatException ignored) {
                    // Ignore unparseable partials
                }
            }
        }
        return sum + ";" + count;
    }

    @Override
    public String reduce(String key, List<String> values) {
        return combine(key, values);
    }

    public static final String NO_LATENCY_MEASURED = "No latency measured";

    @Override
    public String formatResult(String key, String value) {
        if (value == null || value.isBlank()) {
            return NO_LATENCY_MEASURED;
        }
        String[] parts = value.split(";");
        if (parts.length < 2) {
            return NO_LATENCY_MEASURED;
        }
        try {
            double sum = Double.parseDouble(parts[0]);
            long count = Long.parseLong(parts[1]);
            if (count <= 0) {
                return NO_LATENCY_MEASURED;
            }
            double avg = sum / count;
            return String.format(Locale.ROOT, "%.2f ms average over %d events", avg, count);
        } catch (NumberFormatException ignored) {
            return NO_LATENCY_MEASURED;
        }
    }
}
