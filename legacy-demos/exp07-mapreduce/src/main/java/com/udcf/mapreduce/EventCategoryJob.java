package com.udcf.mapreduce;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Project-specific job: how many events of each category did the framework produce?
 *
 * <p>The input is the framework's own event log, exported from the earlier experiments.
 * This is what makes MapReduce part of this project rather than a standalone exercise —
 * the system is analysing its own behaviour.</p>
 */
public class EventCategoryJob implements MapReduceJob {

    @Override public String name()        { return "event-category-count"; }
    @Override public String description() { return "How many events of each category did the cluster produce?"; }

    @Override
    public void map(String line, BiConsumer<String, String> emit) {
        String category = LogFields.field(line, "category");
        if (category != null) {
            emit.accept(category, "1");
        }
        // A line that does not parse emits nothing at all. Silently skipping malformed
        // input is normal in MapReduce: one bad record must not fail the whole job.
    }

    @Override
    public String combine(String key, List<String> values) {
        long total = 0;
        for (String v : values) {
            total += Long.parseLong(v);
        }
        return String.valueOf(total);
    }

    @Override
    public String reduce(String key, List<String> values) {
        return combine(key, values);
    }
}
