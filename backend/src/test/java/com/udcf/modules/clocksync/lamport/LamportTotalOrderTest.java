package com.udcf.modules.clocksync.lamport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LamportTotalOrderTest {

    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");
    private final LamportTotalOrder comparator = LamportTotalOrder.INSTANCE;

    @Test
    @DisplayName("rejects null arguments")
    void rejectsNulls() {
        ClockEvent e = ClockEvent.local(1, 1, "test", now);
        assertThatThrownBy(() -> comparator.compare(null, e))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> comparator.compare(e, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("orders strictly by lamportTime when timestamps differ")
    void ordersByLamportTimeAscending() {
        ClockEvent early = ClockEvent.local(3, 10, "node 3 event", now);
        ClockEvent late = ClockEvent.local(1, 15, "node 1 event", now);

        assertThat(comparator.compare(early, late)).isNegative();
        assertThat(comparator.compare(late, early)).isPositive();
    }

    @Test
    @DisplayName("breaks ties using nodeId ascending when lamport timestamps are identical")
    void breaksTiesUsingNodeIdAscending() {
        ClockEvent node1Event = ClockEvent.local(1, 20, "concurrent on node 1", now);
        ClockEvent node2Event = ClockEvent.local(2, 20, "concurrent on node 2", now);
        ClockEvent node3Event = ClockEvent.local(3, 20, "concurrent on node 3", now);

        assertThat(comparator.compare(node1Event, node2Event)).isNegative();
        assertThat(comparator.compare(node2Event, node3Event)).isNegative();
        assertThat(comparator.compare(node3Event, node1Event)).isPositive();
    }

    @Test
    @DisplayName("treats identical lamportTime and nodeId as equal")
    void returnsZeroForIdenticalKey() {
        ClockEvent a = ClockEvent.local(2, 42, "description a", now);
        ClockEvent b = ClockEvent.send(2, 42, 3, "description b", now.plusMillis(10));

        assertThat(comparator.compare(a, b)).isZero();
    }

    @Test
    @DisplayName("sorts a shuffled list of concurrent and sequential events deterministically")
    void sortsShuffledListDeterministically() {
        ClockEvent e1 = ClockEvent.local(1, 1, "e1", now);
        ClockEvent e2 = ClockEvent.local(2, 1, "e2 (concurrent with e1)", now);
        ClockEvent e3 = ClockEvent.send(1, 2, 2, "e3", now);
        ClockEvent e4 = ClockEvent.receive(2, 3, 1, 2, "e4", now);
        ClockEvent e5 = ClockEvent.local(3, 3, "e5 (concurrent with e4)", now);

        List<ClockEvent> events = new ArrayList<>(List.of(e4, e2, e5, e1, e3));
        events.sort(comparator);

        assertThat(events).containsExactly(e1, e2, e3, e4, e5);
    }
}
