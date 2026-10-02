package com.udcf.mapreduce;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * The classic MapReduce example: count how often each word appears.
 *
 * <p>Included because it is the job every description of MapReduce uses, so it makes the
 * mechanism easy to follow before the project-specific jobs are introduced.</p>
 */
public class WordCountJob implements MapReduceJob {

    @Override public String name()        { return "word-count"; }
    @Override public String description() { return "How many times does each word appear?"; }

    /** Split a line into words and emit one pair per word. */
    @Override
    public void map(String line, BiConsumer<String, String> emit) {
        for (String token : line.toLowerCase().split("[^a-z0-9]+")) {
            if (!token.isBlank()) {
                emit.accept(token, "1");
            }
        }
    }

    /** Summing is associative, so the same code works locally and globally. */
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
