package com.udcf.replication;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe collector of replication events from all three nodes.
 *
 * <p>The primary's replication threads, the backups' connection-handler threads and the
 * demo thread all write here at once, so an ordinary list would corrupt. Events are
 * ordered by the pair (lamportTime, nodeId) — the same total order established in
 * Experiment 3.</p>
 */
public class ReplicationEventLog {

    private final ConcurrentLinkedQueue<ReplicationEvent> events = new ConcurrentLinkedQueue<>();

    public void record(ReplicationEvent event) {
        events.add(event);
    }

    public List<ReplicationEvent> causallyOrdered() {
        List<ReplicationEvent> snapshot = new ArrayList<>(events);
        Comparator<ReplicationEvent> order =
                Comparator.<ReplicationEvent>comparingLong(ReplicationEvent::lamportTime)
                        .thenComparingInt(ReplicationEvent::nodeId);
        snapshot.sort(order);
        return snapshot;
    }

    public long countByCategory(String category) {
        long n = 0;
        for (ReplicationEvent e : events) {
            if (e.category().equals(category)) {
                n++;
            }
        }
        return n;
    }

    public int size() {
        return events.size();
    }
}
