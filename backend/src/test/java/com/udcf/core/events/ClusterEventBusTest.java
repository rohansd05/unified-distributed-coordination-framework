package com.udcf.core.events;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Guards the bus's ordering guarantee (sequence = buffer = dispatch order), that publishing
 * never blocks on a slow subscriber, and that the dispatcher thread stops on close.
 */
class ClusterEventBusTest {

    private static final Instant WALL = Instant.parse("2026-03-04T05:06:07Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private ClusterEventBus bus;

    @AfterEach
    void tearDown() {
        if (bus != null) {
            bus.close();
        }
    }

    private ClusterEventBus newBus(int bufferSize, int queueSize) {
        bus = new ClusterEventBus(new EventProperties(bufferSize, queueSize),
                Clock.fixed(WALL, ZoneOffset.UTC));
        return bus;
    }

    private static EventDraft draft(int nodeId, long lamportTime) {
        return EventDraft.of("test", nodeId, "T", lamportTime);
    }

    private static List<Long> sequences(List<ClusterEvent> events) {
        synchronized (events) {
            return events.stream().map(ClusterEvent::sequence).toList();
        }
    }

    @Test
    @DisplayName("sequences start at 1 and increase by one")
    void sequencesStartAtOne() {
        newBus(10, 10);

        assertThat(bus.publish(draft(1, 1)).sequence()).isEqualTo(1);
        assertThat(bus.publish(draft(1, 2)).sequence()).isEqualTo(2);
        assertThat(bus.publish(draft(2, 1)).sequence()).isEqualTo(3);
    }

    @Test
    @DisplayName("20 concurrent publishers get gap-free sequences, dispatched in sequence order")
    void concurrentPublishersGapFree() throws Exception {
        int threads = 20;
        int perThread = 500;
        int total = threads * perThread;
        newBus(total, total);
        List<ClusterEvent> received = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(received::add);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<Long>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int node = t + 1;
                futures.add(pool.submit(() -> {
                    start.await();
                    List<Long> mine = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        mine.add(bus.publish(draft(node, i)).sequence());
                    }
                    return mine;
                }));
            }
            start.countDown();
            List<Long> assigned = new ArrayList<>();
            for (Future<List<Long>> future : futures) {
                assigned.addAll(future.get(30, TimeUnit.SECONDS));
            }

            List<Long> expected = LongStream.rangeClosed(1, total).boxed().toList();
            assertThat(assigned).containsExactlyInAnyOrderElementsOf(expected);
            await().atMost(TIMEOUT).until(() -> received.size() == total);
            assertThat(sequences(received)).containsExactlyElementsOf(expected);
            assertThat(bus.droppedNotifications()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("an invalid draft is rejected without consuming a sequence number")
    void invalidDraftConsumesNoSequence() {
        newBus(10, 10);
        bus.publish(draft(1, 1));

        assertThatIllegalArgumentException().isThrownBy(() -> bus.publish(EventDraft.of("", 1, "T", 0)));

        assertThat(bus.publish(draft(1, 2)).sequence()).isEqualTo(2);
    }

    @Test
    @DisplayName("wall time comes from the injected clock")
    void wallTimeFromClock() {
        newBus(10, 10);

        assertThat(bus.publish(draft(1, 1)).wallTime()).isEqualTo(WALL);
    }

    @Test
    @DisplayName("subscribers receive events in sequence order")
    void subscribersReceiveInOrder() {
        newBus(500, 500);
        List<ClusterEvent> received = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(received::add);

        for (int i = 0; i < 200; i++) {
            bus.publish(draft(1 + i % 3, 200 - i));   // Lamport times deliberately out of order
        }

        await().atMost(TIMEOUT).until(() -> received.size() == 200);
        assertThat(sequences(received)).containsExactlyElementsOf(
                LongStream.rangeClosed(1, 200).boxed().toList());
    }

    @Test
    @DisplayName("closing a subscription stops delivery to it")
    void unsubscribeStopsDelivery() {
        newBus(10, 10);
        List<ClusterEvent> first = Collections.synchronizedList(new ArrayList<>());
        List<ClusterEvent> second = Collections.synchronizedList(new ArrayList<>());
        ClusterEventBus.Subscription subscription = bus.subscribe(first::add);
        bus.subscribe(second::add);

        bus.publish(draft(1, 1));
        await().atMost(TIMEOUT).until(() -> first.size() == 1 && second.size() == 1);
        subscription.close();
        subscription.close();   // idempotent
        bus.publish(draft(1, 2));

        // The single dispatcher delivers to subscribers in registration order, so once the
        // second subscriber has event 2, the first would already have had it.
        await().atMost(TIMEOUT).until(() -> second.size() == 2);
        assertThat(sequences(first)).containsExactly(1L);
    }

    @Test
    @DisplayName("a throwing subscriber does not stop delivery to the others")
    void throwingSubscriberIsIsolated() {
        newBus(10, 10);
        List<ClusterEvent> received = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(event -> {
            throw new IllegalStateException("subscriber failure (expected by this test)");
        });
        bus.subscribe(received::add);

        bus.publish(draft(1, 1));
        bus.publish(draft(1, 2));
        bus.publish(draft(1, 3));

        await().atMost(TIMEOUT).until(() -> received.size() == 3);
        assertThat(sequences(received)).containsExactly(1L, 2L, 3L);
        assertThat(bus.isDispatcherAlive()).isTrue();
    }

    @Test
    @DisplayName("a blocked subscriber never blocks publish: overflow is counted, the buffer keeps everything")
    void blockedSubscriberDropsNotifications() throws Exception {
        newBus(100, 2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        bus.subscribe(event -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            bus.publish(draft(1, 1));
            assertThat(entered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            // The dispatcher now holds event 1 and is stuck in the subscriber.

            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                bus.publish(draft(1, 2));   // fills the queue
                bus.publish(draft(1, 3));   // fills the queue
                for (int i = 4; i <= 8; i++) {
                    bus.publish(draft(1, i));   // queue full: dropped
                }
            });

            assertThat(bus.droppedNotifications()).isEqualTo(5);
            assertThat(bus.query(null, null, 100)).hasSize(8);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("the dispatcher is a daemon thread named udcf-event-dispatcher")
    void dispatcherThreadIdentity() {
        newBus(10, 10);
        List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(event -> threads.add(Thread.currentThread()));

        bus.publish(draft(1, 1));

        await().atMost(TIMEOUT).until(() -> threads.size() == 1);
        assertThat(threads.get(0).getName()).isEqualTo("udcf-event-dispatcher");
        assertThat(threads.get(0).isDaemon()).isTrue();
    }

    @Test
    @DisplayName("close stops the dispatcher thread and is idempotent")
    void closeStopsDispatcher() {
        newBus(10, 10);
        assertThat(bus.isDispatcherAlive()).isTrue();

        bus.close();
        bus.close();

        await().atMost(TIMEOUT).until(() -> !bus.isDispatcherAlive());
    }

    @Test
    @DisplayName("after close, events are still recorded but count as dropped notifications")
    void publishAfterClose() {
        newBus(10, 10);
        bus.close();

        ClusterEvent event = bus.publish(draft(1, 1));

        assertThat(event.sequence()).isEqualTo(1);
        assertThat(bus.query(null, null, 10)).containsExactly(event);
        assertThat(bus.droppedNotifications()).isEqualTo(1);
    }

    @Test
    @DisplayName("query delegates to the ring buffer with its filters and causal order")
    void queryDelegates() {
        newBus(10, 10);
        bus.publish(EventDraft.of("election", 2, "T", 5));
        bus.publish(EventDraft.of("replication", 1, "T", 1));
        bus.publish(EventDraft.of("election", 1, "T", 5));

        assertThat(sequences(bus.query("election", null, 10))).containsExactly(3L, 1L);
        assertThat(sequences(bus.query(null, 1, 10))).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("clearHistory empties the history and sequence numbers continue")
    void clearHistoryKeepsSequence() {
        newBus(10, 10);
        bus.publish(draft(1, 1));
        bus.publish(draft(1, 2));

        bus.clearHistory();

        assertThat(bus.query(null, null, 10)).isEmpty();
        ClusterEvent next = bus.publish(draft(1, 3));
        assertThat(next.sequence()).isEqualTo(3);
        assertThat(bus.query(null, null, 10)).containsExactly(next);
    }

    @Test
    @DisplayName("clearHistory keeps the dropped-notification count")
    void clearHistoryKeepsDroppedCount() {
        newBus(10, 10);
        bus.close();
        bus.publish(draft(1, 1));   // after close: counted as dropped

        bus.clearHistory();

        assertThat(bus.droppedNotifications()).isEqualTo(1);
    }

    @Test
    @DisplayName("events already queued for subscribers are still delivered in order after clearHistory")
    void clearHistoryKeepsQueuedEvents() throws Exception {
        newBus(100, 100);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<ClusterEvent> received = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(event -> {
            if (event.sequence() == 1) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            received.add(event);
        });

        try {
            bus.publish(draft(1, 1));
            assertThat(entered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            bus.publish(draft(1, 2));
            bus.publish(draft(1, 3));

            bus.clearHistory();
        } finally {
            release.countDown();
        }

        await().atMost(TIMEOUT).until(() -> received.size() == 3);
        assertThat(sequences(received)).containsExactly(1L, 2L, 3L);
    }
}
