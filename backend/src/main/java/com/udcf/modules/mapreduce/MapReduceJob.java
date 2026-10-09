package com.udcf.modules.mapreduce;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * One MapReduce job: the algorithm logic implemented by the user of the framework.
 *
 * <p>Everything else — splitting input, shipping work across nodes, grouping by key,
 * hash-partitioning to reducers, retrying failures — is handled by the framework.
 * That separation is the core principle of MapReduce: programmers author two pure
 * functions and the platform manages distributed coordination.</p>
 */
public interface MapReduceJob {

    /** Name used to identify the job across network tasks and registry lookups. */
    String name();

    /** One-line description shown in documentation and the user interface. */
    String description();

    /**
     * Map phase. Called once per input line on whichever worker holds that split.
     *
     * <p>Emits zero or more key-value pairs via {@code emit}. Nothing is shared
     * between calls, allowing map work to execute concurrently without coordination.</p>
     */
    void map(String line, BiConsumer<String, String> emit);

    /**
     * Combine phase: a localized reduce executed on the mapper node before network transfer.
     *
     * <p>Valid only when the operation is associative, as the framework may apply it
     * zero, one, or several times across intermediate stages.</p>
     */
    String combine(String key, List<String> values);

    /**
     * Reduce phase: called once per unique key with every value gathered for that key.
     */
    String reduce(String key, List<String> values);

    /** Whether the framework should run {@link #combine} on mapper nodes. */
    default boolean usesCombiner() {
        return true;
    }

    /** Formats the final key and value for presentation. Defaults to raw value. */
    default String formatResult(String key, String value) {
        return value;
    }
}
