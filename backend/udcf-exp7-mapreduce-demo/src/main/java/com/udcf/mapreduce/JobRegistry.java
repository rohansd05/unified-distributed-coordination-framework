package com.udcf.mapreduce;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps a job name to its implementation.
 *
 * <p>A worker receives only the job's <i>name</i> in a task message, never the code, so
 * it needs a way to look up which functions to run. In Hadoop this is solved by shipping
 * a JAR to every node; here every node already has the same classes, so a name is enough.</p>
 */
public final class JobRegistry {

    private static final Map<String, MapReduceJob> JOBS = new LinkedHashMap<>();

    static {
        register(new WordCountJob());
        register(new EventCategoryJob());
        register(new LatencyPerNodeJob());
    }

    private JobRegistry() { }

    public static void register(MapReduceJob job) {
        JOBS.put(job.name(), job);
    }

    public static MapReduceJob get(String name) {
        MapReduceJob job = JOBS.get(name);
        if (job == null) {
            throw new IllegalArgumentException("unknown job: " + name);
        }
        return job;
    }
}
