package com.udcf.modules.faulttolerance;

import com.udcf.modules.replication.ApplyResult;
import com.udcf.modules.replication.ConsistencyModel;
import com.udcf.modules.replication.DataItem;
import com.udcf.modules.replication.WriteResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The update stream with a scripted submitter: the test completes every update itself, so
 * nothing depends on timing. No sockets, no ports. The rate is 1000 per second (1 ms apart).
 */
class UpdateStreamTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final int RATE = 1000;

    /** One update the stream sent; the test completes {@code future}. */
    private record Sent(SystemUpdate update, ConsistencyModel model, CompletableFuture<UpdateOutcome> future) {
    }

    private final LinkedBlockingQueue<Sent> sent = new LinkedBlockingQueue<>();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger maxPending = new AtomicInteger();
    private final List<UpdateOutcome> outcomes = new CopyOnWriteArrayList<>();
    private final List<UpdateStream.Snapshot> stops = new CopyOnWriteArrayList<>();
    private UpdateStream stream;

    private UpdateStream stream(int maxUpdates) {
        stream = new UpdateStream((update, model) -> {
            maxPending.accumulateAndGet(pending.incrementAndGet(), Math::max);
            CompletableFuture<UpdateOutcome> future = new CompletableFuture<>();
            future.whenComplete((o, e) -> pending.decrementAndGet());
            sent.add(new Sent(update, model, future));
            return future;
        }, new UpdateStream.Listener() {
            @Override
            public void onOutcome(UpdateOutcome outcome) {
                outcomes.add(outcome);
            }

            @Override
            public void onStopped(UpdateStream.Snapshot snapshot) {
                stops.add(snapshot);
            }
        }, maxUpdates, Clock.systemUTC(), System::nanoTime);
        return stream;
    }

    @AfterEach
    void tearDown() {
        if (stream != null) {
            stream.close();
        }
    }

    private Sent next() throws InterruptedException {
        Sent next = sent.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(next).as("the stream sent no update within " + WAIT).isNotNull();
        return next;
    }

    static UpdateOutcome accepted(SystemUpdate update, ConsistencyModel model) {
        WriteResult result = new WriteResult(new DataItem(update.key(), update.value(), 1, 1, 2), model,
                ApplyResult.APPLIED, 1.0, model == ConsistencyModel.ASYNCHRONOUS ? 400 : 0, List.of(),
                CompletableFuture.completedFuture(List.of()));
        return UpdateOutcome.accepted(update, 1, 1, result);
    }

    private static void accept(Sent s) {
        s.future().complete(accepted(s.update(), s.model()));
    }

    @Test
    @DisplayName("sends numbered updates one at a time: never two in flight, the next only after the previous ended")
    void sendsOneAtATimeInSequence() throws InterruptedException {
        stream(100).start(ConsistencyModel.SYNCHRONOUS, RATE);
        for (int i = 1; i <= 5; i++) {
            Sent s = next();
            assertThat(s.update()).isEqualTo(SystemUpdate.numbered(i));
            assertThat(stream.snapshot().inFlightSequence()).isEqualTo(i);
            accept(s);
        }
        await().atMost(WAIT).until(() -> stream.snapshot().acknowledged() == 5);
        assertThat(maxPending.get()).isEqualTo(1);
        assertThat(outcomes).hasSizeGreaterThanOrEqualTo(5);
        assertThat(stream.snapshot().remainingUpdates()).isLessThanOrEqualTo(95);
    }

    @Test
    @DisplayName("a mode change applies from the next update, while the stream runs")
    void modeChangeAppliesToNextUpdate() throws InterruptedException {
        stream(100).start(ConsistencyModel.SYNCHRONOUS, RATE);
        Sent first = next();
        assertThat(stream.setMode(ConsistencyModel.ASYNCHRONOUS)).isEqualTo(ConsistencyModel.SYNCHRONOUS);
        accept(first);
        assertThat(first.model()).isEqualTo(ConsistencyModel.SYNCHRONOUS);
        assertThat(next().model()).isEqualTo(ConsistencyModel.ASYNCHRONOUS);
        assertThat(stream.snapshot().mode()).isEqualTo(ConsistencyModel.ASYNCHRONOUS);
    }

    @Test
    @DisplayName("stop sends nothing more; the update in flight still ends, is counted and reported")
    void stopPreventsNextAndCountsInFlight() throws InterruptedException {
        stream(100).start(ConsistencyModel.SYNCHRONOUS, RATE);
        Sent first = next();
        assertThat(stream.stop()).isTrue();
        assertThat(stops).singleElement().satisfies(s -> {
            assertThat(s.stopReason()).isEqualTo(UpdateStream.StopReason.REQUESTED);
            assertThat(s.running()).isFalse();
        });
        accept(first);
        await().atMost(WAIT).until(() -> stream.snapshot().acknowledged() == 1);
        assertThat(stream.snapshot().inFlightSequence()).isNull();
        assertThat(outcomes).hasSize(1);
        assertThat(stream.stop()).isFalse();
        // nothing was sent after the stop: a new stream goes on with update 2
        stream.start(ConsistencyModel.SYNCHRONOUS, RATE);
        assertThat(next().update().sequence()).isEqualTo(2);
    }

    @Test
    @DisplayName("a second start while running is refused and changes nothing")
    void startWhileRunningIsRefused() throws InterruptedException {
        stream(100).start(ConsistencyModel.SYNCHRONOUS, RATE);
        next();
        assertThat(stream.start(ConsistencyModel.ASYNCHRONOUS, 1)).isEqualTo(UpdateStream.StartResult.ALREADY_RUNNING);
        assertThat(stream.snapshot().ratePerSecond()).isEqualTo(RATE);
        assertThat(stream.snapshot().mode()).isEqualTo(ConsistencyModel.SYNCHRONOUS);
    }

    @Test
    @DisplayName("restart while an update is in flight: the new stream waits for it and does not count it")
    void restartWaitsForUpdateInFlight() throws InterruptedException {
        stream(100).start(ConsistencyModel.SYNCHRONOUS, RATE);
        Sent first = next();
        stream.stop();
        assertThat(stream.start(ConsistencyModel.ASYNCHRONOUS, RATE)).isEqualTo(UpdateStream.StartResult.STARTED);
        assertThat(sent).isEmpty();   // nothing can be sent while update 1 is in flight
        accept(first);
        Sent second = next();
        assertThat(second.update().sequence()).isEqualTo(2);
        assertThat(second.model()).isEqualTo(ConsistencyModel.ASYNCHRONOUS);
        assertThat(stream.snapshot().acknowledged()).isZero();   // update 1 belongs to the first stream
        assertThat(outcomes).hasSize(1);
        assertThat(maxPending.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("the session budget stops the stream with LIMIT_REACHED; a new start is refused until reset")
    void limitReachedStopsAndRefuses() throws InterruptedException {
        stream(3).start(ConsistencyModel.SYNCHRONOUS, RATE);
        for (int i = 0; i < 3; i++) {
            accept(next());
        }
        await().atMost(WAIT).until(() -> !stops.isEmpty());
        UpdateStream.Snapshot stopped = stops.get(0);
        assertThat(stopped.stopReason()).isEqualTo(UpdateStream.StopReason.LIMIT_REACHED);
        assertThat(stopped.remainingUpdates()).isZero();
        assertThat(stopped.maxUpdates()).isEqualTo(3);
        assertThat(stream.start(ConsistencyModel.SYNCHRONOUS, RATE)).isEqualTo(UpdateStream.StartResult.LIMIT_REACHED);
        stream.reset();
        assertThat(stream.snapshot().remainingUpdates()).isEqualTo(3);
        assertThat(stream.start(ConsistencyModel.SYNCHRONOUS, RATE)).isEqualTo(UpdateStream.StartResult.STARTED);
        assertThat(next().update().sequence()).isEqualTo(4);   // sequences go on after a reset
    }

    @Test
    @DisplayName("an update that gave up is counted as gave up, and the stream goes on")
    void gaveUpIsCounted() throws InterruptedException {
        stream(100).start(ConsistencyModel.SYNCHRONOUS, RATE);
        Sent first = next();
        first.future().complete(UpdateOutcome.gaveUp(first.update(), first.model(), 50));
        accept(next());
        await().atMost(WAIT).until(() -> stream.snapshot().acknowledged() == 1);
        assertThat(stream.snapshot().gaveUp()).isEqualTo(1);
        assertThat(stream.snapshot().running()).isTrue();
    }

    @Test
    @DisplayName("a failed future or a throwing submitter stops the stream with ERROR")
    void errorStopsTheStream() throws InterruptedException {
        stream(100).start(ConsistencyModel.SYNCHRONOUS, RATE);
        next().future().completeExceptionally(new IllegalStateException("Experiment 8 is closed"));
        await().atMost(WAIT).until(() -> !stops.isEmpty());
        assertThat(stops.get(0).stopReason()).isEqualTo(UpdateStream.StopReason.ERROR);
        assertThat(stream.snapshot().inFlightSequence()).isNull();

        UpdateStream throwing = new UpdateStream((u, m) -> {
            throw new IllegalStateException("boom");
        }, new UpdateStream.Listener() {
            @Override
            public void onOutcome(UpdateOutcome outcome) {
            }

            @Override
            public void onStopped(UpdateStream.Snapshot snapshot) {
                stops.add(snapshot);
            }
        }, 10, Clock.systemUTC(), System::nanoTime);
        try {
            throwing.start(ConsistencyModel.SYNCHRONOUS, RATE);
            await().atMost(WAIT).until(() -> stops.size() == 2);
            assertThat(stops.get(1).stopReason()).isEqualTo(UpdateStream.StopReason.ERROR);
        } finally {
            throwing.close();
        }
    }

    @Test
    @DisplayName("reset: an outcome sent before it is neither counted nor reported; the budget is fresh")
    void resetIgnoresEarlierOutcome() throws InterruptedException {
        stream(10).start(ConsistencyModel.ASYNCHRONOUS, RATE);
        Sent first = next();
        stream.reset();
        accept(first);
        UpdateStream.Snapshot after = stream.snapshot();
        assertThat(after.running()).isFalse();
        assertThat(after.acknowledged()).isZero();
        assertThat(after.ratePerSecond()).isNull();
        assertThat(after.startedAt()).isNull();
        assertThat(after.stopReason()).isNull();
        assertThat(after.mode()).isEqualTo(ConsistencyModel.SYNCHRONOUS);
        assertThat(after.remainingUpdates()).isEqualTo(10);
        assertThat(outcomes).isEmpty();
        assertThat(stops).isEmpty();
    }

    @Test
    @DisplayName("close stops the stream thread")
    void closeJoinsTheThread() throws InterruptedException {
        stream(10).start(ConsistencyModel.SYNCHRONOUS, RATE);
        next();
        stream.close();
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(
                t -> t.getName().equals(UpdateStream.THREAD_NAME) && t.isAlive());
        stream = null;
    }
}
