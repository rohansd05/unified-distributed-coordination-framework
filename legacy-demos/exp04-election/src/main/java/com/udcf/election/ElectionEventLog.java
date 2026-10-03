package com.udcf.election;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe collector of election events from every node.
 *
 * <p>All five node threads write to this concurrently, so an ordinary ArrayList would
 * corrupt. Events are ordered by the pair (lamportTime, nodeId), which is exactly the
 * total order established in Experiment 3 — this is why the election log can be replayed
 * correctly even though UDP gives no delivery-order guarantee.</p>
 */
public class ElectionEventLog {

    private final ConcurrentLinkedQueue<ElectionEvent> events = new ConcurrentLinkedQueue<>();

    public void record(ElectionEvent event) {
        events.add(event);
    }

    public List<ElectionEvent> causallyOrdered() {
        List<ElectionEvent> snapshot = new ArrayList<>(events);
        Comparator<ElectionEvent> order =
                Comparator.<ElectionEvent>comparingLong(ElectionEvent::lamportTime)
                        .thenComparingInt(ElectionEvent::nodeId);
        snapshot.sort(order);
        return snapshot;
    }

    public long countByCategory(String category) {
        long count = 0;
        for (ElectionEvent e : events) {
            if (e.category().equals(category)) {
                count++;
            }
        }
        return count;
    }

    public int size() {
        return events.size();
    }
}
