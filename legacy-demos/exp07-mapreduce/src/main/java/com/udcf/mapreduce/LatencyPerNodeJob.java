package com.udcf.mapreduce;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Project-specific job: what is the average event latency on each node?
 *
 * <p>This job exists to show something word count cannot. An average is <b>not</b>
 * associative — you cannot average a set of averages and get the right answer. The job
 * therefore carries a partial sum and a partial count through the pipeline as
 * {@code sum;count}, which <i>is</i> associative, and only divides at the very end.</p>
 *
 * <p>Getting this wrong is one of the most common MapReduce mistakes, which is why it is
 * worth demonstrating deliberately.</p>
 */
public class LatencyPerNodeJob implements MapReduceJob {

    @Override public String name()        { return "avg-latency-per-node"; }
    @Override public String description() { return "What is the average event latency on each node?"; }

    @Override
    public void map(String line, BiConsumer<String, String> emit) {
        String node = LogFields.field(line, "node");
        String latency = LogFields.field(line, "latency");
        if (node == null || latency == null) {
            return;
        }
        try {
            double value = Double.parseDouble(latency);
            emit.accept("node-" + node, value + ";1");   // partial sum, partial count
        } catch (NumberFormatException e) {
            // Unparseable latency: skip this record rather than fail the job.
        }
    }

    /** Adds partials together. Summing pairs IS associative, unlike averaging. */
    @Override
    public String combine(String key, List<String> values) {
        double sum = 0;
        long count = 0;
        for (String v : values) {
            String[] parts = v.split(";");
            sum += Double.parseDouble(parts[0]);
            count += Long.parseLong(parts[1]);
        }
        return sum + ";" + count;
    }

    /** Only here, with every partial collected, is the division performed. */
    @Override
    public String reduce(String key, List<String> values) {
        return combine(key, values);
    }

    @Override
    public String formatResult(String key, String value) {
        String[] parts = value.split(";");
        double sum = Double.parseDouble(parts[0]);
        long count = Long.parseLong(parts[1]);
        double avg = count == 0 ? 0 : sum / count;
        return String.format("%.2f ms average over %d events", avg, count);
    }
}
