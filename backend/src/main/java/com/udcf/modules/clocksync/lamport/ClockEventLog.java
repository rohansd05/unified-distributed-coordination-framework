package com.udcf.modules.clocksync.lamport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe collector of clock events across all nodes in the cluster.
 *
 * <p>Every node thread writes to this concurrently. Under concurrent execution, a plain list
 * would corrupt or throw {@link java.util.ConcurrentModificationException}; therefore a
 * {@link ConcurrentLinkedQueue} is used. At query time, events can be retrieved either in arrival
 * snapshot order or sorted into {@link #causallyOrdered()} total order.</p>
 *
 * <p>Supports an optional capacity limit; when the log exceeds its capacity, the oldest events
 * are evicted first and a dropped events counter is incremented.</p>
 */
public class ClockEventLog {

    private final int capacity;
    private final ConcurrentLinkedQueue<ClockEvent> events = new ConcurrentLinkedQueue<>();
    private final AtomicLong droppedCount = new AtomicLong(0);

    public ClockEventLog(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, was " + capacity);
        }
        this.capacity = capacity;
    }

    public ClockEventLog() {
        this(Integer.MAX_VALUE);
    }

    public void record(ClockEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        events.add(event);
        while (events.size() > capacity) {
            ClockEvent dropped = events.poll();
            if (dropped != null) {
                droppedCount.incrementAndGet();
            }
        }
    }

    public int capacity() {
        return capacity;
    }

    public long droppedCount() {
        return droppedCount.get();
    }

    /**
     * Returns an unmodifiable list of all recorded events sorted according to the total causal
     * order: {@code (lamportTime, nodeId)}.
     */
    public List<ClockEvent> causallyOrdered() {
        List<ClockEvent> snapshot = new ArrayList<>(events);
        snapshot.sort(LamportTotalOrder.INSTANCE);
        return Collections.unmodifiableList(snapshot);
    }

    /**
     * Counts how many events of the given {@code type} occurred on {@code nodeId}.
     */
    public long countForNode(int nodeId, ClockEventType type) {
        Objects.requireNonNull(type, "type must not be null");
        long count = 0;
        for (ClockEvent e : events) {
            if (e.nodeId() == nodeId && e.type() == type) {
                count++;
            }
        }
        return count;
    }

    /**
     * Counts how many events of the given {@code type} occurred across all nodes.
     */
    public long countByType(ClockEventType type) {
        Objects.requireNonNull(type, "type must not be null");
        long count = 0;
        for (ClockEvent e : events) {
            if (e.type() == type) {
                count++;
            }
        }
        return count;
    }

    /**
     * Total number of events currently in the log.
     */
    public int size() {
        return events.size();
    }

    /**
     * Clears all recorded events from the log.
     */
    public void clear() {
        events.clear();
        droppedCount.set(0);
    }

    /**
     * Returns an unmodifiable snapshot of events in arrival order.
     */
    public List<ClockEvent> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }
}
