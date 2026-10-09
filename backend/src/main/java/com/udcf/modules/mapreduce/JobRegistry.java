package com.udcf.modules.mapreduce;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry mapping job names to their algorithm implementations.
 */
public class JobRegistry {

    private final Map<String, MapReduceJob> jobs = new ConcurrentHashMap<>();

    public JobRegistry() {
    }

    /**
     * Creates a new registry preloaded with the standard UDCF MapReduce jobs.
     */
    public static JobRegistry standard() {
        JobRegistry registry = new JobRegistry();
        registry.register(new WordCountJob());
        registry.register(new EventCategoryJob());
        registry.register(new LatencyPerNodeJob());
        return registry;
    }

    public void register(MapReduceJob job) {
        Objects.requireNonNull(job, "job must not be null");
        jobs.put(job.name(), job);
    }

    public MapReduceJob get(String name) {
        if (name == null) {
            throw new IllegalArgumentException("job name must not be null");
        }
        MapReduceJob job = jobs.get(name);
        if (job == null) {
            throw new IllegalArgumentException("unknown job: " + name);
        }
        return job;
    }

    public boolean contains(String name) {
        return name != null && jobs.containsKey(name);
    }

    public Collection<MapReduceJob> all() {
        return Collections.unmodifiableCollection(jobs.values());
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(jobs.keySet());
    }
}
