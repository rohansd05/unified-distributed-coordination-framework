package com.udcf.core.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The cluster-wide event log and its live notification stream, shared by every module.
 *
 * <p>{@link #publish(EventDraft)} assigns the next sequence number, stamps the wall time,
 * appends the event to the ring buffer and offers it to a bounded dispatch queue, all
 * under one lock. Sequence order is therefore buffer order is dispatch order.</p>
 *
 * <p>Publishing never blocks on subscribers. If the dispatch queue is full, the event is
 * still recorded in the buffer (so {@link #query} sees it) but subscribers miss it, and
 * {@link #droppedNotifications()} counts it. A slow WebSocket client can therefore never
 * stall an election or a replication round.</p>
 *
 * <p>One daemon thread, {@value #DISPATCHER_THREAD_NAME}, delivers events to subscribers
 * in sequence order. A subscriber that throws is logged and the others still receive the
 * event.</p>
 */
public class ClusterEventBus implements AutoCloseable {

    /** An {@link AutoCloseable} whose {@code close()} throws no checked exception. */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    static final String DISPATCHER_THREAD_NAME = "udcf-event-dispatcher";
    private static final long CLOSE_JOIN_MILLIS = 2000;
    private static final Logger log = LoggerFactory.getLogger(ClusterEventBus.class);

    private final Clock clock;
    private final EventRingBuffer buffer;
    private final BlockingQueue<ClusterEvent> dispatchQueue;
    private final List<Consumer<ClusterEvent>> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicLong dropped = new AtomicLong();
    private final Object publishLock = new Object();
    private final Thread dispatcher;

    private long sequence;              // guarded by publishLock
    private volatile boolean closed;

    public ClusterEventBus(EventProperties properties, Clock clock) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.buffer = new EventRingBuffer(properties.bufferSize());
        this.dispatchQueue = new ArrayBlockingQueue<>(properties.dispatchQueueSize());
        this.dispatcher = new Thread(this::dispatchLoop, DISPATCHER_THREAD_NAME);
        this.dispatcher.setDaemon(true);
        this.dispatcher.start();
    }

    /**
     * Records an event and queues it for subscribers. Never blocks.
     *
     * <p>After {@link #close()} the event is still recorded in the buffer but is not
     * dispatched, and it counts as a dropped notification.</p>
     *
     * @return the stamped event
     * @throws IllegalArgumentException if the draft fails {@link ClusterEvent} validation;
     *                                  no sequence number is consumed in that case
     */
    public ClusterEvent publish(EventDraft draft) {
        Objects.requireNonNull(draft, "draft must not be null");
        synchronized (publishLock) {
            long next = sequence + 1;
            ClusterEvent event = new ClusterEvent(next, draft.module(), draft.nodeId(),
                    draft.type(), draft.lamportTime(), Instant.now(clock), draft.peerId(),
                    draft.message(), draft.data());
            sequence = next;
            buffer.append(event);
            if (closed || !dispatchQueue.offer(event)) {
                dropped.incrementAndGet();
            }
            return event;
        }
    }

    /** Registers a subscriber; closing the returned subscription unsubscribes it. */
    public Subscription subscribe(Consumer<ClusterEvent> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber must not be null");
        subscribers.add(subscriber);
        AtomicBoolean active = new AtomicBoolean(true);
        return () -> {
            if (active.compareAndSet(true, false)) {
                subscribers.remove(subscriber);
            }
        };
    }

    /** See {@link EventRingBuffer#query(String, Integer, int)}. */
    public List<ClusterEvent> query(String module, Integer nodeId, int limit) {
        return buffer.query(module, nodeId, limit);
    }

    /** Events recorded in the buffer that subscribers never received. */
    public long droppedNotifications() {
        return dropped.get();
    }

    /** Stops the dispatcher thread. Idempotent; called by Spring on context shutdown. */
    @Override
    public void close() {
        synchronized (publishLock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        dispatcher.interrupt();
        try {
            dispatcher.join(CLOSE_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (dispatcher.isAlive()) {
            log.warn("Event dispatcher did not stop within {} ms; a subscriber is still running",
                    CLOSE_JOIN_MILLIS);
        }
    }

    /** Package-visible so tests can check the dispatcher stopped. */
    boolean isDispatcherAlive() {
        return dispatcher.isAlive();
    }

    private void dispatchLoop() {
        while (!closed) {
            ClusterEvent event;
            try {
                event = dispatchQueue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (event != null && !closed) {
                deliver(event);
            }
        }
    }

    private void deliver(ClusterEvent event) {
        for (Consumer<ClusterEvent> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException e) {
                log.warn("Subscriber failed on event #{} ({}/{}); continuing with the others",
                        event.sequence(), event.module(), event.type(), e);
            }
        }
    }
}
