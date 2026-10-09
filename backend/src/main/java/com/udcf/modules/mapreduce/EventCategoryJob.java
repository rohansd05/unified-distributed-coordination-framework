package com.udcf.modules.mapreduce;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Project-specific MapReduce job: count cluster events grouped by category.
 */
public class EventCategoryJob implements MapReduceJob {

    @Override
    public String name() {
        return "event-category-count";
    }

    @Override
    public String description() {
        return "How many events of each category did the cluster produce?";
    }

    @Override
    public void map(String line, BiConsumer<String, String> emit) {
        String category = LogFields.field(line, "category");
        if (category != null && !category.isBlank()) {
            emit.accept(category, "1");
        }
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
