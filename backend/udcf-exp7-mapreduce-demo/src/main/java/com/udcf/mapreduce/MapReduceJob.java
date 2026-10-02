package com.udcf.mapreduce;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * One MapReduce job: the only thing a user of the framework has to write.
 *
 * <p>Everything else — splitting the input, shipping work to nodes, grouping by key,
 * partitioning to reducers, retrying failures — is handled by the framework. That
 * separation is the whole idea of MapReduce: the programmer writes two small functions
 * and the system takes care of running them across many machines.</p>
 */
public interface MapReduceJob {

    /** Name used to identify the job when a task is shipped to a worker. */
    String name();

    /** One-line description shown in the demonstration. */
    String description();

    /**
     * Map phase. Called once per input line on whichever node holds that split.
     *
     * <p>Emits zero or more key-value pairs. Nothing is shared between calls, which is
     * exactly why map work can be split across nodes without any coordination.</p>
     */
    void map(String line, BiConsumer<String, String> emit);

    /**
     * Combine phase: a reduce run locally on the node that produced the pairs,
     * before anything crosses the network.
     *
     * <p>This is an optimisation, not a requirement. It is only valid when the reduce
     * operation is associative, because the framework may apply it zero, one or several
     * times. For a sum it is safe; for something like a median it would be wrong.</p>
     */
    String combine(String key, List<String> values);

    /** Reduce phase: called once per key, with every value collected for that key. */
    String reduce(String key, List<String> values);

    /** Whether the framework should run {@link #combine} on the mapper nodes. */
    default boolean usesCombiner() {
        return true;
    }

    /** How the final value should be shown. Lets a job format its own output. */
    default String formatResult(String key, String value) {
        return value;
    }
}
