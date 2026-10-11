package com.udcf.modules.rmi;

import com.udcf.modules.rmi.dto.HistoryEntryDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The last calls and comparisons, newest first, bounded to {@code history-size}, with one
 * sequential id sequence starting at 1.
 *
 * <p>A generation number makes reset safe while a call is still finishing: {@link #clear} forgets
 * every entry, starts a new generation and restarts the ids at 1, and an id {@link #reserve}d in an
 * older generation can no longer {@link #record} itself. Thread safe.</p>
 */
final class RmiHistory {

    /** An id handed out for one entry, in the generation it was handed out in. */
    record Slot(long generation, long id) {
    }

    private final int capacity;
    private final List<HistoryEntryDto> entries = new ArrayList<>();   // index 0 is the newest
    private long generation;
    private long nextId = 1;

    RmiHistory(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, was " + capacity);
        }
        this.capacity = capacity;
    }

    /** The next id, in the current generation. */
    synchronized Slot reserve() {
        return new Slot(generation, nextId++);
    }

    /**
     * Adds {@code entry} as the newest and drops the oldest beyond the capacity.
     *
     * @return false, with nothing changed, if {@code slot} belongs to an older generation
     */
    synchronized boolean record(Slot slot, HistoryEntryDto entry) {
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

    synchronized Optional<HistoryEntryDto> find(long id) {
        return entries.stream().filter(entry -> entry.id() == id).findFirst();
    }

    synchronized Optional<HistoryEntryDto> latest() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(0));
    }

    /** Every kept entry, newest first. */
    synchronized List<HistoryEntryDto> all() {
        return List.copyOf(entries);
    }

    /** Forgets every entry, starts a new generation and restarts the ids at 1. */
    synchronized void clear() {
        entries.clear();
        generation++;
        nextId = 1;
    }
}
