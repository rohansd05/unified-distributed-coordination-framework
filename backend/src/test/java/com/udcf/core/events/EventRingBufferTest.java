package com.udcf.core.events;

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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Guards bounded memory (eviction), the query filters, and that query results come back
 * in causal order rather than arrival order.
 */
class EventRingBufferTest {

    private static final Instant WALL = Instant.parse("2026-01-01T00:00:00Z");

    private static ClusterEvent event(long sequence, String module, int nodeId, long lamportTime) {
        return new ClusterEvent(sequence, module, nodeId, "T", lamportTime, WALL, null, null, null);
    }

    private static List<Long> sequences(List<ClusterEvent> events) {
        return events.stream().map(ClusterEvent::sequence).toList();
    }

    @Test
    @DisplayName("append grows the size up to capacity")
    void appendAndSize() {
        EventRingBuffer buffer = new EventRingBuffer(5);

        buffer.append(event(1, "m", 1, 1));
        buffer.append(event(2, "m", 1, 2));

        assertThat(buffer.size()).isEqualTo(2);
        assertThat(buffer.evictedCount()).isZero();
    }

    @Test
    @DisplayName("snapshot returns events in arrival order, oldest first")
    void snapshotInArrivalOrder() {
        EventRingBuffer buffer = new EventRingBuffer(5);
        buffer.append(event(1, "m", 1, 9));
        buffer.append(event(2, "m", 1, 3));
        buffer.append(event(3, "m", 1, 5));

        assertThat(sequences(buffer.snapshot())).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("when full, the oldest events are evicted and counted")
    void wrapAroundEvicts() {
        EventRingBuffer buffer = new EventRingBuffer(3);
        for (long s = 1; s <= 7; s++) {
            buffer.append(event(s, "m", 1, s));
        }

        assertThat(buffer.size()).isEqualTo(3);
        assertThat(buffer.evictedCount()).isEqualTo(4);
        assertThat(sequences(buffer.snapshot())).containsExactly(5L, 6L, 7L);
    }

    @Test
    @DisplayName("query filters by module")
    void queryFiltersByModule() {
        EventRingBuffer buffer = new EventRingBuffer(10);
        buffer.append(event(1, "election", 1, 1));
        buffer.append(event(2, "replication", 1, 2));
        buffer.append(event(3, "election", 2, 3));

        assertThat(sequences(buffer.query("election", null, 10))).containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("query filters by node")
    void queryFiltersByNode() {
        EventRingBuffer buffer = new EventRingBuffer(10);
        buffer.append(event(1, "election", 1, 1));
        buffer.append(event(2, "replication", 2, 2));
        buffer.append(event(3, "election", 2, 3));

        assertThat(sequences(buffer.query(null, 2, 10))).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("query combines the module and node filters")
    void queryCombinesFilters() {
        EventRingBuffer buffer = new EventRingBuffer(10);
        buffer.append(event(1, "election", 1, 1));
        buffer.append(event(2, "replication", 2, 2));
        buffer.append(event(3, "election", 2, 3));

        assertThat(sequences(buffer.query("election", 2, 10))).containsExactly(3L);
    }

    @Test
    @DisplayName("the limit keeps the most recent events by arrival, not by Lamport time")
    void limitTakesMostRecentByArrival() {
        EventRingBuffer buffer = new EventRingBuffer(10);
        buffer.append(event(1, "m", 1, 100));   // oldest arrival, highest Lamport time
        buffer.append(event(2, "m", 1, 5));
        buffer.append(event(3, "m", 1, 7));
        buffer.append(event(4, "m", 1, 6));

        assertThat(sequences(buffer.query(null, null, 3))).containsExactly(2L, 4L, 3L);
    }

    @Test
    @DisplayName("results are sorted by Lamport time, then node id, then sequence")
    void causalSort() {
        EventRingBuffer buffer = new EventRingBuffer(10);
        buffer.append(event(1, "m", 3, 5));
        buffer.append(event(2, "m", 1, 9));
        buffer.append(event(3, "m", 2, 5));     // ties on Lamport time with #1; lower node
        buffer.append(event(4, "m", 2, 5));     // ties on Lamport time and node with #3
        buffer.append(event(5, "m", 1, 2));

        assertThat(sequences(buffer.query(null, null, 10))).containsExactly(5L, 3L, 4L, 1L, 2L);
    }

    @Test
    @DisplayName("rejects a capacity below one")
    void rejectsInvalidCapacity() {
        assertThatIllegalArgumentException().isThrownBy(() -> new EventRingBuffer(0));
        assertThatIllegalArgumentException().isThrownBy(() -> new EventRingBuffer(-5));
    }

    @Test
    @DisplayName("rejects a query limit below one")
    void rejectsInvalidLimit() {
        EventRingBuffer buffer = new EventRingBuffer(3);

        assertThatIllegalArgumentException().isThrownBy(() -> buffer.query(null, null, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> buffer.query(null, null, -1));
    }

    @Test
    @DisplayName("20 threads appending concurrently lose nothing up to capacity")
    void concurrentAppendsLoseNothing() throws Exception {
        int threads = 20;
        int perThread = 500;
        EventRingBuffer buffer = new EventRingBuffer(threads * perThread);
        AtomicLong sequence = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int node = t + 1;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        buffer.append(event(sequence.incrementAndGet(), "m", node, i));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }

            assertThat(buffer.size()).isEqualTo(threads * perThread);
            assertThat(buffer.evictedCount()).isZero();
            assertThat(sequences(buffer.snapshot())).doesNotHaveDuplicates()
                    .hasSize(threads * perThread);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("clear empties the buffer and resets the evicted count")
    void clearEmptiesAndResetsEvicted() {
        EventRingBuffer buffer = new EventRingBuffer(3);
        for (long s = 1; s <= 5; s++) {
            buffer.append(event(s, "m", 1, s));
        }

        buffer.clear();

        assertThat(buffer.size()).isZero();
        assertThat(buffer.evictedCount()).isZero();
        assertThat(buffer.snapshot()).isEmpty();
        assertThat(buffer.query(null, null, 10)).isEmpty();
    }

    @Test
    @DisplayName("after clear the buffer fills and wraps around normally")
    void worksNormallyAfterClear() {
        EventRingBuffer buffer = new EventRingBuffer(3);
        buffer.append(event(1, "m", 1, 1));
        buffer.append(event(2, "m", 1, 2));
        buffer.clear();

        for (long s = 10; s <= 13; s++) {
            buffer.append(event(s, "m", 1, s));
        }

        assertThat(sequences(buffer.snapshot())).containsExactly(11L, 12L, 13L);
        assertThat(buffer.evictedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("clear during concurrent appends leaves a consistent buffer")
    void clearIsSafeUnderConcurrentAppends() throws Exception {
        int threads = 20;
        int perThread = 500;
        EventRingBuffer buffer = new EventRingBuffer(1000);
        AtomicLong sequence = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        buffer.append(event(sequence.incrementAndGet(), "m", 1, i));
                    }
                    return null;
                }));
            }
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 50; i++) {
                    buffer.clear();
                }
                return null;
            }));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }

            List<ClusterEvent> snapshot = buffer.snapshot();
            assertThat(snapshot).hasSize(buffer.size()).doesNotContainNull();
            assertThat(buffer.size()).isLessThanOrEqualTo(1000);
        } finally {
            pool.shutdownNow();
        }
    }
}
