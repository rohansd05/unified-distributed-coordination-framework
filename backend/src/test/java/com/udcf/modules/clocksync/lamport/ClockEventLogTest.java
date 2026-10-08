package com.udcf.modules.clocksync.lamport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClockEventLogTest {

    private final Instant now = Instant.parse("2026-10-07T12:00:00Z");

    @Test
    @DisplayName("records and returns events in arrival order via snapshot")
    void recordsAndReturnsSnapshot() {
        ClockEventLog log = new ClockEventLog();
        ClockEvent e1 = ClockEvent.local(1, 1, "first", now);
        ClockEvent e2 = ClockEvent.send(1, 2, 2, "second", now);

        log.record(e1);
        log.record(e2);

        assertThat(log.size()).isEqualTo(2);
        assertThat(log.snapshot()).containsExactly(e1, e2);
    }

    @Test
    @DisplayName("rejects null event")
    void rejectsNullEvent() {
        ClockEventLog log = new ClockEventLog();
        assertThatThrownBy(() -> log.record(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("causallyOrdered returns total causal order snapshot without mutating log")
    void causallyOrderedReturnsSortedList() {
        ClockEventLog log = new ClockEventLog();
        ClockEvent e3 = ClockEvent.local(2, 5, "late", now);
        ClockEvent e1 = ClockEvent.local(1, 2, "early", now);
        ClockEvent e2 = ClockEvent.local(2, 2, "concurrent", now);

        log.record(e3);
        log.record(e1);
        log.record(e2);

        List<ClockEvent> ordered = log.causallyOrdered();

        assertThat(ordered).containsExactly(e1, e2, e3);
        // Original snapshot preserves arrival order
        assertThat(log.snapshot()).containsExactly(e3, e1, e2);
    }

    @Test
    @DisplayName("counts events accurately by node and by type")
    void countsEventsAccurately() {
        ClockEventLog log = new ClockEventLog();
        log.record(ClockEvent.local(1, 1, "loc1", now));
        log.record(ClockEvent.local(1, 2, "loc2", now));
        log.record(ClockEvent.send(1, 3, 2, "snd1", now));
        log.record(ClockEvent.receive(2, 4, 1, 3, "rcv1", now));
        log.record(ClockEvent.local(2, 5, "loc3", now));

        assertThat(log.countForNode(1, ClockEventType.LOCAL)).isEqualTo(2);
        assertThat(log.countForNode(1, ClockEventType.SEND)).isEqualTo(1);
        assertThat(log.countForNode(1, ClockEventType.RECV)).isEqualTo(0);

        assertThat(log.countForNode(2, ClockEventType.RECV)).isEqualTo(1);
        assertThat(log.countForNode(2, ClockEventType.LOCAL)).isEqualTo(1);

        assertThat(log.countByType(ClockEventType.LOCAL)).isEqualTo(3);
        assertThat(log.countByType(ClockEventType.SEND)).isEqualTo(1);
        assertThat(log.countByType(ClockEventType.RECV)).isEqualTo(1);
    }

    @Test
    @DisplayName("clear empties the log")
    void clearEmptiesLog() {
        ClockEventLog log = new ClockEventLog();
        log.record(ClockEvent.local(1, 1, "e1", now));
        assertThat(log.size()).isEqualTo(1);

        log.clear();
        assertThat(log.size()).isZero();
        assertThat(log.snapshot()).isEmpty();
        assertThat(log.causallyOrdered()).isEmpty();
    }

    @Test
    @DisplayName("concurrent writes across 20 threads record all events without loss or corruption")
    void concurrentWritesMaintainsIntegrity() throws Exception {
        int threads = 20;
        int eventsPerThread = 250;
        ClockEventLog log = new ClockEventLog();
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 1; t <= threads; t++) {
                final int nodeId = t;
                futures.add(pool.submit(() -> {
                    startLatch.await();
                    for (int i = 1; i <= eventsPerThread; i++) {
                        log.record(ClockEvent.local(nodeId, i, "event " + i, now));
                    }
                    return null;
                }));
            }

            startLatch.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }

            int expectedTotal = threads * eventsPerThread;
            assertThat(log.size()).isEqualTo(expectedTotal);
            assertThat(log.causallyOrdered()).hasSize(expectedTotal);
            assertThat(log.countByType(ClockEventType.LOCAL)).isEqualTo(expectedTotal);
        } finally {
            pool.shutdownNow();
        }
    }
}
