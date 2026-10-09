package com.udcf.modules.mapreduce;

import com.udcf.modules.mapreduce.dto.RunDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The last runs, newest first, bounded to a configured size.
 *
 * <p>A generation number makes reset safe while a run is active: {@link #clear} starts a new
 * generation, and a run started in an older one can no longer {@link #record} itself, so a
 * reset history stays empty. Thread-safe.</p>
 */
final class RunHistory {

    private final int capacity;
    private final List<RunDto> runs = new ArrayList<>();   // index 0 is the newest
    private long generation;

    RunHistory(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, was " + capacity);
        }
        this.capacity = capacity;
    }

    synchronized long generation() {
        return generation;
    }

    /**
     * Adds a run as the newest, or replaces the kept run with the same id in place. Drops the
     * oldest runs beyond the capacity.
     *
     * @return false, with nothing changed, if {@code generation} is not the current one
     */
    synchronized boolean record(long generation, RunDto run) {
        Objects.requireNonNull(run, "run must not be null");
        if (generation != this.generation) {
            return false;
        }
        for (int i = 0; i < runs.size(); i++) {
            if (runs.get(i).runId().equals(run.runId())) {
                runs.set(i, run);
                return true;
            }
        }
        runs.add(0, run);
        while (runs.size() > capacity) {
            runs.remove(runs.size() - 1);
        }
        return true;
    }

    /** Removes a run (used when its thread could not start). */
    synchronized void remove(String runId) {
        runs.removeIf(run -> run.runId().equals(runId));
    }

    synchronized Optional<RunDto> find(String runId) {
        return runs.stream().filter(run -> run.runId().equals(runId)).findFirst();
    }

    synchronized Optional<RunDto> latest() {
        return runs.isEmpty() ? Optional.empty() : Optional.of(runs.get(0));
    }

    /** Every kept run, newest first. */
    synchronized List<RunDto> all() {
        return List.copyOf(runs);
    }

    /** Forgets every run and starts a new generation. */
    synchronized void clear() {
        runs.clear();
        generation++;
    }
}
