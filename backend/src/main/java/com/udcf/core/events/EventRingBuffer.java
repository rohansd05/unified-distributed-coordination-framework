package com.udcf.core.events;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Fixed-capacity, thread-safe store of the most recent cluster events.
 *
 * <p>When full, each append evicts the oldest event by arrival. Memory therefore stays
 * bounded however long the cluster runs, which matters on the public profile.</p>
 *
 * <p>All methods synchronise on this instance. Appends are O(1), and queries scan at most
 * {@code capacity} events, so a single lock is simpler than anything finer-grained and
 * costs nothing measurable at these sizes.</p>
 */
public class EventRingBuffer {

    /** Causal order: Lamport time, then node id on a tie, then arrival sequence. */
    public static final Comparator<ClusterEvent> CAUSAL_ORDER = Comparator
            .comparingLong(ClusterEvent::lamportTime)
            .thenComparingInt(ClusterEvent::nodeId)
            .thenComparingLong(ClusterEvent::sequence);

    private final ClusterEvent[] slots;
    private int head;          // index of the oldest event
    private int size;
    private long evicted;

    public EventRingBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, was " + capacity);
        }
        this.slots = new ClusterEvent[capacity];
    }

    public synchronized void append(ClusterEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (size < slots.length) {
            slots[(head + size) % slots.length] = event;
            size++;
        } else {
            slots[head] = event;
            head = (head + 1) % slots.length;
            evicted++;
        }
    }

    /** Removes every event and resets {@link #evictedCount()} to zero. */
    public synchronized void clear() {
        Arrays.fill(slots, null);
        head = 0;
        size = 0;
        evicted = 0;
    }

    public synchronized int size() {
        return size;
    }

    /** Events dropped from the buffer because it was full. */
    public synchronized long evictedCount() {
        return evicted;
    }

    /** Every buffered event in arrival order, oldest first. */
    public synchronized List<ClusterEvent> snapshot() {
        List<ClusterEvent> copy = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            copy.add(slots[(head + i) % slots.length]);
        }
        return copy;
    }

    /**
     * The most recent matching events by arrival, up to {@code limit}, returned in
     * {@link #CAUSAL_ORDER}.
     *
     * @param module filter by module id, or {@code null} for every module
     * @param nodeId filter by node id, or {@code null} for every node
     * @param limit  maximum number of events, at least 1
     */
    public List<ClusterEvent> query(String module, Integer nodeId, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, was " + limit);
        }
        List<ClusterEvent> matches = new ArrayList<>();
        synchronized (this) {
            for (int i = size - 1; i >= 0 && matches.size() < limit; i--) {
                ClusterEvent event = slots[(head + i) % slots.length];
                if ((module == null || module.equals(event.module()))
                        && (nodeId == null || nodeId == event.nodeId())) {
                    matches.add(event);
                }
            }
        }
        matches.sort(CAUSAL_ORDER);
        return matches;
    }
}
