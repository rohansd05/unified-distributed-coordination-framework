package com.udcf.modules.mapreduce;

import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Classic MapReduce job: count how often each alphanumeric word appears.
 */
public class WordCountJob implements MapReduceJob {

    @Override
    public String name() {
        return "word-count";
    }

    @Override
    public String description() {
        return "How many times does each word appear?";
    }

    @Override
    public void map(String line, BiConsumer<String, String> emit) {
        if (line == null) {
            return;
        }
        for (String token : line.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!token.isBlank()) {
                emit.accept(token, "1");
            }
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
