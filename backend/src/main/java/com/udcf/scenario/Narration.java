package com.udcf.scenario;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Plain, sentence-case phrases for step messages and the end state. Every number passed in is an
 * observed value; nothing here invents one.
 */
public final class Narration {

    private Narration() {
    }

    /** "1", "1 and 2", "1, 2 and 3" (ascending); "" when empty. */
    public static String ids(Collection<Integer> ids) {
        List<String> sorted = ids.stream().sorted().map(String::valueOf).toList();
        if (sorted.size() <= 1) {
            return sorted.isEmpty() ? "" : sorted.get(0);
        }
        return String.join(", ", sorted.subList(0, sorted.size() - 1)) + " and " + sorted.get(sorted.size() - 1);
    }

    /** "node 5", "nodes 1 and 2"; "no node" when empty. */
    public static String nodes(Collection<Integer> ids) {
        if (ids.isEmpty()) {
            return "no node";
        }
        return (ids.size() == 1 ? "node " : "nodes ") + ids(ids);
    }

    /** "2504 ms" (rounded to whole milliseconds); "an unmeasured time" for null. */
    public static String millis(Number value) {
        if (value == null) {
            return "an unmeasured time";
        }
        return Math.round(value.doubleValue()) + " ms";
    }

    /** "23.4 %" (one decimal). */
    public static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f %%", value);
    }

    /** The first letter in upper case. */
    public static String sentence(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
