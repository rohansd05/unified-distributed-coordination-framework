package com.udcf.demo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe collector of clock events from all three nodes.
 *
 * <p>Every node thread writes to this concurrently, so a plain ArrayList would corrupt
 * under concurrent modification. At the end of the run the events are sorted by the pair
 * (lamportTime, nodeId), which is what turns Lamport's partial order into a usable total
 * order.</p>
 */
public class ClockEventLog {

    private final ConcurrentLinkedQueue<DemoEvent> events = new ConcurrentLinkedQueue<>();

    public void record(DemoEvent event) {
        events.add(event);
    }

    /**
     * All events in total causal order.
     *
     * <p>The tie-break on nodeId matters: two nodes can legitimately hold the same
     * Lamport value for unrelated (concurrent) events, and without a deterministic
     * tie-break the ordering would differ between runs.</p>
     */
    public List<DemoEvent> causallyOrdered() {
        List<DemoEvent> snapshot = new ArrayList<>(events);
        Comparator<DemoEvent> byLamportThenNode =
                Comparator.<DemoEvent>comparingLong(DemoEvent::lamportTime)
                        .thenComparingInt(DemoEvent::nodeId);
        snapshot.sort(byLamportThenNode);
        return snapshot;
    }

    public long countForNode(int nodeId, String type) {
        long count = 0;
        for (DemoEvent e : events) {
            if (e.nodeId() == nodeId && e.type().equals(type)) {
                count++;
            }
        }
        return count;
    }

    public long countByType(String type) {
        long count = 0;
        for (DemoEvent e : events) {
            if (e.type().equals(type)) {
                count++;
            }
        }
        return count;
    }

    public int size() {
        return events.size();
    }
}
