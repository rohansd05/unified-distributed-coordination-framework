package com.udcf.modules.mpi;

import com.udcf.modules.mpi.dto.CollectiveDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The last collectives, newest first, bounded to {@code history-size}, with ids from 1. The same
 * design as Experiment 1's RmiHistory (package-private there, so not reusable).
 *
 * <p>A generation number makes reset safe while a collective is still running: {@link #clear}
 * forgets every entry and restarts the ids, and a slot reserved before it can no longer record
 * itself. {@link #cancel} gives back the newest reservation when a collective could not finish, so
 * no id is left reserved without an entry. Thread safe.</p>
 */
final class MpiHistory {

    /** An id handed out for one collective, in the generation it was handed out in. */
    record Slot(long generation, long id) {
    }

    private final int capacity;
    private final List<CollectiveDto> entries = new ArrayList<>();   // index 0 is the newest
    private long generation;
    private long nextId = 1;

    MpiHistory(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, was " + capacity);
        }
        this.capacity = capacity;
    }

    synchronized Slot reserve() {
        return new Slot(generation, nextId++);
    }

    /**
     * Gives the id back if {@code slot} is the newest reservation of this generation (otherwise
     * nothing changes: a later id was already handed out, or a reset happened).
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
    synchronized boolean record(Slot slot, CollectiveDto entry) {
        Objects.requireNonNull(slot, "slot must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        if (entry.id() != slot.id()) {
            throw new IllegalArgumentException("entry " + entry.id() + " does not match slot " + slot.id());
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

    synchronized Optional<CollectiveDto> find(long id) {
        return entries.stream().filter(entry -> entry.id() == id).findFirst();
    }

    synchronized Optional<CollectiveDto> latest() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(0));
    }

    /** Every kept entry, newest first. */
    synchronized List<CollectiveDto> all() {
        return List.copyOf(entries);
    }

    /** Forgets every entry, starts a new generation and restarts the ids at 1. */
    synchronized void clear() {
        entries.clear();
        generation++;
        nextId = 1;
    }
}
