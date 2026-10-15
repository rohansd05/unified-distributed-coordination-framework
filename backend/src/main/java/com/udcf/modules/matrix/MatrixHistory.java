package com.udcf.modules.matrix;

import com.udcf.modules.matrix.dto.MatrixRunDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The last matrix runs, newest first, bounded to {@code udcf.matrix.history-size}, with run ids from 1. The
 * same design as Experiment 9's MpiHistory (package-private there and typed to its DTO, so not reusable).
 *
 * <p>A generation number makes reset safe while a run is still going: {@link #clear} forgets every entry and
 * restarts the ids, and a slot reserved before it can no longer record itself. {@link #cancel} gives back the
 * newest reservation when a run could not finish, so no id is left reserved without an entry. Thread safe.</p>
 */
final class MatrixHistory {

    /** A run id handed out for one run, in the generation it was handed out in. */
    record Slot(long generation, long id) {
    }

    private final int capacity;
    private final List<MatrixRunDto> entries = new ArrayList<>();   // index 0 is the newest
    private long generation;
    private long nextId = 1;

    MatrixHistory(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, was " + capacity);
        }
        this.capacity = capacity;
    }

    synchronized Slot reserve() {
        return new Slot(generation, nextId++);
    }

    /**
     * Gives the id back if {@code slot} is the newest reservation of this generation (otherwise nothing
     * changes: a later id was already handed out, or a reset happened).
     */
    synchronized void cancel(Slot slot) {
        Objects.requireNonNull(slot, "slot must not be null");
        if (slot.generation() == generation && slot.id() == nextId - 1) {
            nextId--;
        }
    }

    /**
     * Adds {@code entry} as the newest and drops the oldest beyond the capacity.
     *
     * @return false, with nothing changed, if {@code slot} belongs to an older generation
     */
    synchronized boolean record(Slot slot, MatrixRunDto entry) {
        Objects.requireNonNull(slot, "slot must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        if (entry.runId() != slot.id()) {
            throw new IllegalArgumentException("entry " + entry.runId() + " does not match slot " + slot.id());
        }
        if (slot.generation() != generation) {
            return false;
        }
        entries.add(0, entry);
        while (entries.size() > capacity) {
            entries.remove(entries.size() - 1);
        }
        return true;
    }

    synchronized Optional<MatrixRunDto> find(long id) {
        return entries.stream().filter(entry -> entry.runId() == id).findFirst();
    }

    synchronized Optional<MatrixRunDto> latest() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(0));
    }

    /** Every kept entry, newest first. */
    synchronized List<MatrixRunDto> all() {
        return List.copyOf(entries);
    }

    /** Forgets every entry, starts a new generation and restarts the ids at 1. */
    synchronized void clear() {
        entries.clear();
        generation++;
        nextId = 1;
    }
}
