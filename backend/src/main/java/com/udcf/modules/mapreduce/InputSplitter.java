package com.udcf.modules.mapreduce;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Splits input lines into contiguous, evenly-sized blocks for mapper tasks.
 */
public final class InputSplitter {

    private InputSplitter() { }

    /**
     * Splits {@code input} into {@code parts} contiguous sublists.
     *
     * <p>If {@code input} has fewer lines than {@code parts}, trailing sublists
     * will be empty. Empty sublists are skipped during pipeline execution.</p>
     */
    public static List<List<String>> split(List<String> input, int parts) {
        if (parts <= 0) {
            throw new IllegalArgumentException("parts must be positive: " + parts);
        }
        Objects.requireNonNull(input, "input must not be null");

        List<List<String>> splits = new ArrayList<>(parts);
        if (input.isEmpty()) {
            for (int i = 0; i < parts; i++) {
                splits.add(new ArrayList<>());
            }
            return splits;
        }

        int size = (int) Math.ceil(input.size() / (double) parts);
        if (size <= 0) {
            size = 1;
        }

        for (int i = 0; i < input.size(); i += size) {
            splits.add(new ArrayList<>(input.subList(i, Math.min(input.size(), i + size))));
        }
        while (splits.size() < parts) {
            splits.add(new ArrayList<>());
        }
        return splits;
    }
}
